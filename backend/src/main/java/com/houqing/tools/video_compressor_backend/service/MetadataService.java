package com.houqing.tools.video_compressor_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.dto.MetadataModifyRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class MetadataService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataService.class);

    @Value("${file.upload-dir}")
    private String uploadDir;
    @Value("${file.output-dir}")
    private String outputDir;
    @Value("${ffmpeg.ffprobe-path}")
    private String ffprobePath;
    @Value("${imagemagick.magick-path}")
    private String magickPath;
    @Value("${ffmpeg.ffmpeg-path}")
    private String ffmpegPath;

    private final ImageService imageService; // 复用 executeProcess

    public MetadataService(ImageService imageService) {
        this.imageService = imageService;
    }

    // 保存文件
    public String saveUploadedFile(MultipartFile file) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String uniqueFilename = UUID.randomUUID().toString() + "_" + originalFilename;
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        Path filePath = uploadPath.resolve(uniqueFilename);
        file.transferTo(filePath.toFile());
        return uniqueFilename;
    }

    // =========================
    // 查看元数据（视频音频：翻译官；图片：精细化提取）
    // =========================
    public Map<String, Object> viewMetadata(String uniqueFilename) throws Exception {
        Path inputFilePath = Paths.get(uploadDir).resolve(uniqueFilename);
        String filenameLower = uniqueFilename.toLowerCase();

        if (isVideoOrAudio(filenameLower)) {
            // --- 对视频/音频进行精细化提取并“翻译” ---
            List<String> command = Arrays.asList(
                    ffprobePath, "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", inputFilePath.toString()
            );
            String commandOutput = executeAndGetOutput(command);
            Map<String, Object> rawData = new ObjectMapper().readValue(commandOutput, Map.class);
            return translateFfprobeOutput(rawData);
        } else {
            // 图片使用已实现的精细化提取
            return extractDetailedImageMetadata(inputFilePath);
        }
    }


    // “翻译官”：把 ffprobe JSON 翻成友好可读的分组（增强 HDR 识别）
    @SuppressWarnings("unchecked")
    private Map<String, Object> translateFfprobeOutput(Map<String, Object> rawData) {
        Map<String, Object> finalMetadata = new LinkedHashMap<>();

        try {
            // ---- 1) 文件信息（format 节点）----
            Map<String, Object> formatInfo = (Map<String, Object>) rawData.get("format");
            if (formatInfo != null) {
                Map<String, String> readableFormat = new LinkedHashMap<>();
                readableFormat.put("文件名", str(formatInfo.get("filename")));
                readableFormat.put("文件格式", strOr(formatInfo.get("format_long_name"), "N/A"));

                double duration = parseDouble(formatInfo.get("duration"), 0.0);
                readableFormat.put("时长", formatDuration(duration));

                long size = parseLong(formatInfo.get("size"), 0L);
                readableFormat.put("文件大小", humanSize(size));

                long bitRate = parseLong(formatInfo.get("bit_rate"), 0L);
                if (bitRate > 0) readableFormat.put("总码率", humanBitrate(bitRate));

                Object tagsObj = formatInfo.get("tags");
                Map<String, Object> tags = null;
                if (tagsObj instanceof Map<?, ?> t) {
                    // 转成 <String,Object>
                    tags = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : t.entrySet()) {
                        tags.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    String creation = str(tags.get("creation_time"));
                    if (!creation.isEmpty()) readableFormat.put("创建时间", creation);
                    String encoder = str(tags.get("encoder"));
                    if (!encoder.isEmpty()) readableFormat.put("容器编码器", encoder);
                    String title = str(tags.get("title"));
                    if (!title.isEmpty()) readableFormat.put("标题", title);
                }

                finalMetadata.put("文件信息", readableFormat);

                // ---------- 新增：从 format.tags 中解析视频 GPS ----------
                if (tags != null) {
                    // 1) 优先 Apple 的 ISO6709：+lat+lng/ 或 -lat-lng/
                    String iso6709 = str(tags.get("com.apple.quicktime.location.ISO6709"));
                    double[] latlng = null;

                    if (!iso6709.isEmpty()) {
                        String s = iso6709.replace("/", "").trim();
                        // 用正则捕获：([+-]经纬度)([+-]经纬度)(可选高度)
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("^([+-][0-9.]+)([+-][0-9.]+)([+-][0-9.]+)?$")
                                .matcher(s);
                        if (m.find()) {
                            double lat = Double.parseDouble(m.group(1));
                            double lng = Double.parseDouble(m.group(2));
                            latlng = new double[]{lat, lng};
                        }
                    }

                    // 2) 其他常见键的兜底（有些设备会放到 location / gps 里）
                    if (latlng == null) {
                        String loc = str(tags.get("location"));
                        if (loc.isEmpty()) loc = str(tags.get("gps"));
                        if (!loc.isEmpty()) {
                            // 试着解析 "lat,lon" 或 "lat lon"
                            String norm = loc.trim().replace(",", " ").replace("  ", " ");
                            String[] parts = norm.split("\\s+");
                            if (parts.length >= 2) {
                                try {
                                    double lat = Double.parseDouble(parts[0]);
                                    double lng = Double.parseDouble(parts[1]);
                                    latlng = new double[]{lat, lng};
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    if (latlng != null) {
                        double lat = latlng[0];
                        double lng = latlng[1];
                        Map<String, Object> readableGps = new LinkedHashMap<>();
                        readableGps.put("纬度", lat);
                        readableGps.put("经度", lng);
                        // 使用你类里已有的高德链接构建工具（WGS84->GCJ-02 内置在 buildAmapLink 里）
                        String mapUrl = buildAmapLink(lat, lng, "拍摄地点");
                        readableGps.put("在地图上查看", mapUrl);
                        finalMetadata.put("地理位置 (GPS)", readableGps);
                    }
                }
                // ---------- 新增结束 ----------
            }

            // ---- 2) 各类流（streams 数组）----
            List<Map<String, Object>> streams = (List<Map<String, Object>>) rawData.get("streams");
            if (streams != null) {
                int vIdx = 0, aIdx = 0, sIdx = 0;
                for (Map<String, Object> stream : streams) {
                    String codecType = str(stream.get("codec_type"));
                    switch (codecType) {
                        case "video" -> {
                            Map<String, String> readableVideo = new LinkedHashMap<>();
                            String codecLong = strOr(stream.get("codec_long_name"), "N/A");
                            readableVideo.put("编码格式", codecLong);

                            Integer w = parseInt(stream.get("width"));
                            Integer h = parseInt(stream.get("height"));
                            if (w != null && h != null) {
                                readableVideo.put("分辨率", w + " x " + h);
                            }

                            String pixFmt = str(stream.get("pix_fmt"));
                            if (!pixFmt.isEmpty()) readableVideo.put("像素格式", pixFmt);

                            String avgFrameRate = str(stream.get("avg_frame_rate"));
                            String fps = fpsFromRational(avgFrameRate);
                            if (!fps.isEmpty()) readableVideo.put("帧率", fps + " fps");

                            long vbr = parseLong(stream.get("bit_rate"), 0L);
                            if (vbr > 0) readableVideo.put("码率", humanBitrate(vbr));

                            String sar = str(stream.get("sample_aspect_ratio"));
                            if (!sar.isEmpty() && !"N/A".equalsIgnoreCase(sar)) readableVideo.put("像素宽高比(SAR)", sar);
                            String dar = str(stream.get("display_aspect_ratio"));
                            if (!dar.isEmpty() && !"N/A".equalsIgnoreCase(dar)) readableVideo.put("显示宽高比(DAR)", dar);

                            Integer rotation = extractRotation(stream);
                            if (rotation != null) readableVideo.put("旋转角度", rotation + "°");

                            String colorPrim = str(stream.get("color_primaries"));
                            String colorTrans = str(stream.get("color_transfer"));
                            String colorSpace = str(stream.get("color_space"));
                            String colorRange = str(stream.get("color_range"));
                            if (!colorSpace.isEmpty()) readableVideo.put("色彩空间", colorSpace);
                            if (!colorPrim.isEmpty()) readableVideo.put("色度原色", colorPrim);
                            if (!colorTrans.isEmpty()) readableVideo.put("色彩传递", colorTrans);
                            if (!colorRange.isEmpty()) readableVideo.put("色彩范围", colorRange);

                            String hdrType = detectHdrType(stream);
                            if (!hdrType.isEmpty()) readableVideo.put("动态范围", hdrType);

                            String bits = str(stream.get("bits_per_raw_sample"));
                            if (bits.isEmpty()) bits = str(stream.get("bits_per_sample"));
                            if (!bits.isEmpty()) readableVideo.put("位深", bits + " bit");

                            String lang = extractLanguage(stream);
                            if (!lang.isEmpty()) readableVideo.put("语言", lang);

                            finalMetadata.put("视频流 #" + vIdx, readableVideo);
                            vIdx++;
                        }
                        case "audio" -> {
                            Map<String, String> readableAudio = new LinkedHashMap<>();
                            readableAudio.put("编码格式", strOr(stream.get("codec_long_name"), "N/A"));

                            String sr = str(stream.get("sample_rate"));
                            if (!sr.isEmpty() && !"N/A".equalsIgnoreCase(sr)) {
                                readableAudio.put("采样率", sr + " Hz");
                            }

                            Integer channels = parseInt(stream.get("channels"));
                            if (channels != null) readableAudio.put("声道数", String.valueOf(channels));
                            String layout = str(stream.get("channel_layout"));
                            if (!layout.isEmpty()) readableAudio.put("声道布局", layout);

                            String sampleFmt = str(stream.get("sample_fmt"));
                            if (!sampleFmt.isEmpty()) readableAudio.put("采样格式", sampleFmt);

                            long abr = parseLong(stream.get("bit_rate"), 0L);
                            if (abr > 0) readableAudio.put("码率", humanBitrate(abr));

                            String lang = extractLanguage(stream);
                            if (!lang.isEmpty()) readableAudio.put("语言", lang);

                            finalMetadata.put("音频流 #" + aIdx, readableAudio);
                            aIdx++;
                        }
                        case "subtitle" -> {
                            Map<String, String> readableSub = new LinkedHashMap<>();
                            readableSub.put("编码格式", strOr(stream.get("codec_long_name"), "N/A"));
                            String lang = extractLanguage(stream);
                            if (!lang.isEmpty()) readableSub.put("语言", lang);
                            Object tagsObj = stream.get("tags");
                            if (tagsObj instanceof Map<?, ?> tags2) {
                                String title = str(tags2.get("title"));
                                if (!title.isEmpty()) readableSub.put("标题", title);
                            }
                            finalMetadata.put("字幕流 #" + sIdx, readableSub);
                            sIdx++;
                        }
                        default -> {
                            // 其他类型流（如数据流），可按需添加
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Failed to translate ffprobe output", e);
            finalMetadata.put("错误", "无法解析视频元数据: " + e.getMessage());
            finalMetadata.put("原始数据", rawData); // 失败时附带原始数据便于排查
        }

        return finalMetadata;
    }


    // =========================
    // 图片元数据精细化提取（基础/EXIF/GPS + 高德地图链接）
    // =========================
    private Map<String, Object> extractDetailedImageMetadata(Path imagePath) {
        Map<String, Object> finalMetadata = new LinkedHashMap<>();

        try {
            // 1) 基础信息
            Map<String, String> basicInfo = executeAndParseMagickFormat(
                    imagePath,
                    "文件格式: %m\\n分辨率: %w x %h\\n色彩空间: %[colorspace]\\n文件大小: %b"
            );
            finalMetadata.put("基础信息", basicInfo);

            // 2) EXIF（拍摄信息）
            Map<String, String> exifInfo = executeAndParseMagickFormat(
                    imagePath,
                    "拍摄设备: %[exif:Model]\\n拍摄时间: %[exif:DateTimeOriginal]\\n光圈值: F%[exif:FNumber]\\n曝光时间: %[exif:ExposureTime]s\\nISO速度: %[exif:ISOSpeedRatings]\\n焦距: %[exif:FocalLength]mm"
            );
            exifInfo.entrySet().removeIf(entry ->
                    entry.getValue() == null
                            || entry.getValue().trim().isEmpty()
                            || "s".equals(entry.getValue().trim())
                            || "mm".equals(entry.getValue().trim())
            );
            if (!exifInfo.isEmpty()) {
                finalMetadata.put("拍摄信息 (EXIF)", exifInfo);
            }

            // 3) GPS（转十进制度 + 生成高德地图链接）
            Map<String, String> gpsInfo = executeAndParseMagickFormat(
                    imagePath,
                    "纬度: %[exif:GPSLatitude]\\n纬度半球: %[exif:GPSLatitudeRef]\\n经度: %[exif:GPSLongitude]\\n经度半球: %[exif:GPSLongitudeRef]\\n海拔: %[exif:GPSAltitude]"
            );
            gpsInfo.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().trim().isEmpty());

            if (!gpsInfo.isEmpty()) {
                Map<String, Object> readableGps = new LinkedHashMap<>();
                Double lat = null, lng = null;

                if (gpsInfo.containsKey("纬度")) {
                    lat = convertDmsToDd(gpsInfo.get("纬度"), gpsInfo.get("纬度半球"));
                    readableGps.put("纬度", lat);
                }
                if (gpsInfo.containsKey("经度")) {
                    lng = convertDmsToDd(gpsInfo.get("经度"), gpsInfo.get("经度半球"));
                    readableGps.put("经度", lng);
                }
                if (gpsInfo.containsKey("海拔")) {
                    readableGps.put("海拔", formatAltitude(gpsInfo.get("海拔")) + " 米");
                }

                if (lat != null && lng != null) {
                    String mapUrl = buildAmapLink(lat, lng, "拍摄地点");
                    readableGps.put("在地图上查看", mapUrl);
                }

                finalMetadata.put("地理位置 (GPS)", readableGps);
            }

        } catch (Exception e) {
            logger.error("Failed to extract detailed image metadata", e);
            finalMetadata.put("错误", "无法解析详细元数据: " + e.getMessage());
        }

        return finalMetadata;
    }

    // 执行 "magick identify -format ..." 并解析为 Map
    private Map<String, String> executeAndParseMagickFormat(Path imagePath, String formatString) throws Exception {
        List<String> command = Arrays.asList(magickPath, "identify", "-format", formatString, imagePath.toString());
        String output = executeAndGetOutput(command);

        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = output.split("\\n");
        for (String line : lines) {
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                result.put(parts[0].trim(), parts[1].trim());
            }
        }
        return result;
    }

    // =========================
    // GPS DMS -> 十进制度 (EXIF 形如 "30/1,0/1,2460/100" + "N")
    // =========================
    private double convertDmsToDd(String dms, String ref) {
        try {
            String[] parts = dms.split(",");
            double degrees = parseRational(parts[0]);
            double minutes = parseRational(parts[1]);
            double seconds = parseRational(parts[2]);

            double dd = degrees + (minutes / 60.0) + (seconds / 3600.0);
            if ("S".equalsIgnoreCase(ref) || "W".equalsIgnoreCase(ref)) {
                dd = -dd;
            }
            return dd;
        } catch (Exception e) {
            return 0.0; // 解析失败返回0
        }
    }
    private double parseRational(String s) {
        String t = s.trim();
        String[] frac = t.split("/");
        if (frac.length == 2) {
            double num = Double.parseDouble(frac[0].trim());
            double den = Double.parseDouble(frac[1].trim());
            if (den == 0) return 0.0;
            return num / den;
        }
        // 有些实现可能直接给出小数
        return Double.parseDouble(t);
    }

    // 格式化海拔 "300/1" -> "300.00"
    private String formatAltitude(String altitude) {
        try {
            String[] parts = altitude.split("/");
            double alt = Double.parseDouble(parts[0].trim()) / Double.parseDouble(parts[1].trim());
            return String.format("%.2f", alt);
        } catch (Exception e) {
            return "N/A";
        }
    }

    // =========================
    // 构建高德地图链接（WGS84 -> GCJ-02 后输出）
    // =========================
    private String buildAmapLink(double wgsLat, double wgsLng, String title) {
        double[] gcj = wgs84ToGcj02(wgsLat, wgsLng); // 高德使用 GCJ-02
        String name = urlEncodeUTF8(title == null ? "拍摄地点" : title);
        return String.format(
                "https://uri.amap.com/marker?position=%f,%f&name=%s&src=toolbox&callnative=0",
                gcj[1], gcj[0], name
        );
    }

    // 坐标系转换：WGS84 -> GCJ-02（高德/腾讯）
    private static final double A = 6378245.0;                  // Krasovsky ellipsoid
    private static final double EE = 0.00669342162296594323;    // eccentricity squared

    private double[] wgs84ToGcj02(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        double mgLat = lat + dLat;
        double mgLng = lng + dLng;
        return new double[]{mgLat, mgLng};
    }
    private boolean outOfChina(double lat, double lng) {
        return (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271);
    }
    private double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }
    private double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
    private String urlEncodeUTF8(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // 删除元数据
    public String removeMetadata(String uniqueFilename) throws Exception {
        Path inputFilePath = Paths.get(uploadDir).resolve(uniqueFilename);
        String outputFilename = "stripped_" + uniqueFilename;
        Path outputFilePath = Paths.get(outputDir).resolve(outputFilename);
        String filenameLower = uniqueFilename.toLowerCase();

        List<String> command = new ArrayList<>();
        if (isVideoOrAudio(filenameLower)) {
            command.add(ffmpegPath);
            command.add("-i");
            command.add(inputFilePath.toString());
            command.add("-map_metadata");
            command.add("-1"); // 移除元数据
            command.add("-c:v");
            command.add("copy"); // 复制视频流
            command.add("-c:a");
            command.add("copy"); // 复制音频流
            command.add(outputFilePath.toString());
        } else {
            command.add(magickPath);
            command.add("convert");
            command.add(inputFilePath.toString());
            command.add("-strip"); // 剥离元数据
            command.add(outputFilePath.toString());
        }

        imageService.executeProcess(command, uniqueFilename, 1); // 超时1分钟
        return outputFilename;
    }
    // 修改/伪造元数据（同时支持视频与图片）
    // 修改/伪造元数据（视频=FFmpeg；图片=IM7 先清 EXIF 再写入）
    public String modifyMetadata(MetadataModifyRequestDTO request) throws Exception {
        String uniqueFilename = request.getUniqueFilename();
        Path inputFilePath = Paths.get(uploadDir).resolve(uniqueFilename);
        String outputFilename = "modified_" + uniqueFilename;
        Path outputFilePath = Paths.get(outputDir).resolve(outputFilename);
        String filenameLower = uniqueFilename.toLowerCase();

        if (!Files.exists(outputFilePath.getParent())) {
            Files.createDirectories(outputFilePath.getParent());
        }

        List<String> command = new ArrayList<>();

        if (isVideoOrAudio(filenameLower)) {
            // ------------------ 视频/音频：FFmpeg 写容器元数据 ------------------
            command.add(ffmpegPath);
            command.add("-y");
            command.add("-i");
            command.add(inputFilePath.toString());
            command.add("-map_metadata"); command.add("0");
            command.add("-movflags");     command.add("use_metadata_tags");

            if (request.getDateTimeOriginal() != null && !request.getDateTimeOriginal().isEmpty()) {
                try {
                    String isoTime = request.getDateTimeOriginal()
                            .replaceFirst(":", "-")
                            .replaceFirst(":", "-")
                            .replace(" ", "T") + ".000000Z";
                    command.add("-metadata"); command.add("creation_time=" + isoTime);
                } catch (Exception e) {
                    logger.warn("Failed to parse date for ffmpeg: {}", request.getDateTimeOriginal());
                }
            }
            if (request.getModel() != null && !request.getModel().isEmpty()) {
                command.add("-metadata"); command.add("model=" + request.getModel());
            }
            if (request.getGpsLatitude() != null && request.getGpsLongitude() != null) {
                String iso6709 = String.format("%+011.6f%+012.6f/", request.getGpsLatitude(), request.getGpsLongitude());
                command.add("-metadata"); command.add("location-eng=" + iso6709);
                command.add("-metadata"); command.add("com.apple.quicktime.location.ISO6709=" + iso6709);
            }

            command.add("-c"); command.add("copy");
            command.add(outputFilePath.toString());

            imageService.executeProcess(command, uniqueFilename, 1);
            return outputFilename;
        }

        // ------------------ 图片：IM7 先精确删除 EXIF，再写入新的 EXIF ------------------
        // IM7 正确用法：magick <in> <ops> <out>   （不要加 "convert"）
        command.add(magickPath);
        command.add(inputFilePath.toString());

        // 1) 只删除 EXIF profile（保留 ICC / XMP 等）
        command.add("+profile"); command.add("exif");

        // 2) 写入新的 EXIF。注意：每个 -set 后跟两个独立参数：<key> <value>
        if (request.getDateTimeOriginal() != null && !request.getDateTimeOriginal().isEmpty()) {
            command.add("-set"); command.add("exif:DateTimeOriginal"); command.add(request.getDateTimeOriginal());
            // 可选：一起补 DateTimeDigitized，提高兼容性
            command.add("-set"); command.add("exif:DateTimeDigitized"); command.add(request.getDateTimeOriginal());
        }
        if (request.getModel() != null && !request.getModel().isEmpty()) {
            command.add("-set"); command.add("exif:Model"); command.add(request.getModel());
        }
        if (request.getGpsLatitude() != null && request.getGpsLongitude() != null) {
            String latDms = convertDdToDms(request.getGpsLatitude());
            String lonDms = convertDdToDms(request.getGpsLongitude());
            String latRef = request.getGpsLatitude()  >= 0 ? "N" : "S";
            String lonRef = request.getGpsLongitude() >= 0 ? "E" : "W";

            command.add("-set"); command.add("exif:GPSLatitude");      command.add(latDms);
            command.add("-set"); command.add("exif:GPSLatitudeRef");   command.add(latRef);
            command.add("-set"); command.add("exif:GPSLongitude");     command.add(lonDms);
            command.add("-set"); command.add("exif:GPSLongitudeRef");  command.add(lonRef);
            command.add("-set"); command.add("exif:GPSMapDatum");      command.add("WGS-84");
            command.add("-set"); command.add("exif:GPSVersionID");     command.add("2,2,0,0");
        }

        // 输出路径放最后
        command.add(outputFilePath.toString());

        imageService.executeProcess(command, uniqueFilename, 1);
        return outputFilename;
    }




    private String convertDdToDms(double dd) {
        dd = Math.abs(dd);
        int degrees = (int) dd;
        double minutesDecimal = (dd - degrees) * 60.0;
        int minutes = (int) minutesDecimal;
        double secondsDecimal = (minutesDecimal - minutes) * 60.0;

        int secondsNumerator = (int) Math.round(secondsDecimal * 10000);
        int secondsDenominator = 10000;

        return String.format("%d/1,%d/1,%d/%d", degrees, minutes, secondsNumerator, secondsDenominator);
    }
    // --- 基础工具 ---
    private boolean isVideoOrAudio(String filename) {
        return filename.endsWith(".mp4") || filename.endsWith(".mov") || filename.endsWith(".mkv") ||
                filename.endsWith(".avi") || filename.endsWith(".mp3") || filename.endsWith(".wav") ||
                filename.endsWith(".m4a") || filename.endsWith(".flac");
    }
    private String executeAndGetOutput(List<String> command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ". Output: " + output);
        }
        return output.toString();
    }

    // --- 辅助格式化/解析 ---
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String strOr(Object o, String def) {
        String s = str(o);
        return s.isEmpty() || "N/A".equalsIgnoreCase(s) ? def : s;
    }
    private static int parseInt(Object o, int def) {
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }
    private static Integer parseInt(Object o) {
        try { return Integer.valueOf(String.valueOf(o)); } catch (Exception e) { return null; }
    }
    private static long parseLong(Object o, long def) {
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return def; }
    }
    private static double parseDouble(Object o, double def) {
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return def; }
    }
    private static String humanBitrate(long bps) {
        if (bps <= 0) return "N/A";
        double kbps = bps / 1000.0;
        if (kbps >= 1000) return String.format("%.1f Mbps", kbps / 1000.0);
        return String.format("%.0f kbps", kbps);
    }
    private static String humanSize(long bytes) {
        if (bytes <= 0) return "0 B";
        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        if (gb >= 1) return String.format("%.2f GB", gb);
        if (mb >= 1) return String.format("%.2f MB", mb);
        return String.format("%.0f KB", kb);
    }
    private static String formatDuration(double seconds) {
        if (seconds <= 0) return "0 秒";
        long totalMs = Math.round(seconds * 1000);
        long s = (totalMs / 1000) % 60;
        long m = (totalMs / (60 * 1000)) % 60;
        long h = totalMs / (3600 * 1000);
        if (h > 0) return String.format("%02d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }
    private static String fpsFromRational(String r) {
        if (r == null || r.isEmpty() || "0/0".equals(r) || "N/A".equalsIgnoreCase(r)) return "";
        try {
            String[] p = r.split("/");
            double num = Double.parseDouble(p[0]);
            double den = Double.parseDouble(p[1]);
            if (den == 0) return "";
            double fps = num / den;
            return (Math.abs(fps - Math.round(fps)) < 1e-3)
                    ? String.format("%.0f", fps)
                    : String.format("%.3f", fps);
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static Integer extractRotation(Map<String, Object> stream) {
        // tags.rotate
        Object tagsObj = stream.get("tags");
        if (tagsObj instanceof Map<?, ?> tags) {
            try {
                Object valObj = tags.get("rotate");
                if (valObj != null) {
                    String val = String.valueOf(valObj).trim();
                    if (!val.isEmpty()) return Integer.parseInt(val);
                }
            } catch (Exception ignored) {}
        }
        // side_data_list -> rotation
        Object sdl = stream.get("side_data_list");
        if (sdl instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object rt = m.get("rotation");
                    try {
                        if (rt != null) return Integer.parseInt(String.valueOf(rt).trim());
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String extractLanguage(Map<String, Object> stream) {
        Object tagsObj = stream.get("tags");
        if (tagsObj instanceof Map<?, ?> tags) {
            Object langObj = tags.get("language");
            if (langObj != null) {
                String lang = String.valueOf(langObj);
                if (!lang.isEmpty()) return lang;
            }
        }
        return "";
    }


    @SuppressWarnings("unchecked")
    private static String detectHdrType(Map<String, Object> stream) {
        String codecName = String.valueOf(stream.getOrDefault("codec_name", "")).toLowerCase(Locale.ROOT);
        String colorTransfer = String.valueOf(stream.getOrDefault("color_transfer", "")).toLowerCase(Locale.ROOT);

        // 读取 side_data_list
        List<Map<String, Object>> sideData = Collections.emptyList();
        Object sdl = stream.get("side_data_list");
        if (sdl instanceof List<?> list) {
            sideData = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    // 将通用 map 转为 <String,Object>
                    Map<String, Object> casted = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        casted.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    sideData.add(casted);
                }
            }
        }

        // 1) Dolby Vision：codec_name dvh1/dvhe 或 side data 包含 Dolby Vision 信息
        if (codecName.startsWith("dvh") || codecName.startsWith("dvhe") || hasDolbyVision(sideData)) {
            String profile = dolbyVisionProfile(sideData);
            return profile.isEmpty() ? "Dolby Vision" : ("Dolby Vision (Profile " + profile + ")");
        }

        // 2) HDR10+：side data 中出现动态 HDR10+ 元数据（SMPTE ST 2094 App 4）
        if (hasHdr10Plus(sideData)) {
            return "HDR10+";
        }

        // 3) HDR10：PQ/2084 且存在 Mastering/CLL
        boolean isPQ = colorTransfer.contains("smpte2084") || colorTransfer.contains("pq");
        if (isPQ && (hasMasteringMetadata(sideData) || hasContentLightMetadata(sideData))) {
            String cll = contentLightSummary(sideData);
            String mastering = masteringSummary(sideData);
            StringBuilder sb = new StringBuilder("HDR10");
            if (!cll.isEmpty() || !mastering.isEmpty()) {
                sb.append(" [");
                if (!cll.isEmpty()) sb.append(cll);
                if (!cll.isEmpty() && !mastering.isEmpty()) sb.append("; ");
                if (!mastering.isEmpty()) sb.append(mastering);
                sb.append("]");
            }
            return sb.toString();
        }

        // 4) HLG：ARIB STD-B67
        if (colorTransfer.contains("arib-std-b67") || colorTransfer.contains("hlg")) {
            return "HDR (HLG)";
        }

        // 其他一律不标注（SDR）
        return "";
    }

    private static boolean hasDolbyVision(List<Map<String, Object>> sideData) {
        for (Map<String, Object> m : sideData) {
            String type = String.valueOf(m.getOrDefault("side_data_type", "")).toLowerCase(Locale.ROOT);
            // ffprobe 可能返回 "DOVI configuration record" / "Dolby Vision"
            if (type.contains("dolby") || type.contains("dovi")) return true;
        }
        return false;
    }

    private static String dolbyVisionProfile(List<Map<String, Object>> sideData) {
        for (Map<String, Object> m : sideData) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (k.contains("dv_profile") || k.contains("dovi_profile")) {
                    return String.valueOf(e.getValue());
                }
            }
        }
        return "";
    }

    private static boolean hasHdr10Plus(List<Map<String, Object>> sideData) {
        for (Map<String, Object> m : sideData) {
            String type = String.valueOf(m.getOrDefault("side_data_type", "")).toLowerCase(Locale.ROOT);
            // ffprobe 常见："HDR10+ Metadata" 或 "SMPTE ST 2094 App 4"
            if (type.contains("hdr10+") || type.contains("2094")) return true;
        }
        return false;
    }

    private static boolean hasMasteringMetadata(List<Map<String, Object>> sideData) {
        for (Map<String, Object> m : sideData) {
            String type = String.valueOf(m.getOrDefault("side_data_type", "")).toLowerCase(Locale.ROOT);
            if (type.contains("mastering")) return true; // "Mastering display metadata"
        }
        return false;
    }

    private static boolean hasContentLightMetadata(List<Map<String, Object>> sideData) {
        for (Map<String, Object> m : sideData) {
            String type = String.valueOf(m.getOrDefault("side_data_type", "")).toLowerCase(Locale.ROOT);
            if (type.contains("content light")) return true; // "Content light level metadata"
        }
        return false;
    }

    private static String contentLightSummary(List<Map<String, Object>> sideData) {
        for (Map<String, Object> m : sideData) {
            String type = String.valueOf(m.getOrDefault("side_data_type", "")).toLowerCase(Locale.ROOT);
            if (type.contains("content light")) {
                String maxCLL = str(m.get("max_content_light_level"));
                String maxFALL = str(m.get("max_pic_average_light_level"));
                List<String> parts = new ArrayList<>();
                if (!maxCLL.isEmpty()) parts.add("MaxCLL " + maxCLL + " nits");
                if (!maxFALL.isEmpty()) parts.add("MaxFALL " + maxFALL + " nits");
                return String.join(", ", parts);
            }
        }
        return "";
    }

    private static String masteringSummary(List<Map<String, Object>> sideData) {
        for (Map<String, Object> m : sideData) {
            String type = String.valueOf(m.getOrDefault("side_data_type", "")).toLowerCase(Locale.ROOT);
            if (type.contains("mastering")) {
                String maxLum = str(m.get("max_luminance"));
                String minLum = str(m.get("min_luminance"));
                List<String> parts = new ArrayList<>();
                if (!maxLum.isEmpty()) parts.add("MaxLum " + maxLum + " nits");
                if (!minLum.isEmpty()) parts.add("MinLum " + minLum + " nits");
                return String.join(", ", parts);
            }
        }
        return "";
    }
}
