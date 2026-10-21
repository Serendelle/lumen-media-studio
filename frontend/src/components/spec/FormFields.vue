<script setup lang="ts">
import type { FormField, FormValues } from '@/api/forms'
defineProps<{ fields: FormField[]; disabled?: boolean; prefix?: string }>()
const values = defineModel<FormValues>({ required: true })
function input(key: string, event: Event) { values.value[key] = (event.target as HTMLInputElement).value }
</script>

<template>
  <div class="form-fields">
    <label v-for="field in fields" :key="field.key" :class="{ wide: field.type === 'LONG_TEXT', check: field.type === 'CHECKBOX' }">
      <span>{{ field.label }} <em v-if="field.required" aria-label="必填">*</em></span>
      <textarea v-if="field.type === 'LONG_TEXT'" :value="String(values[field.key] ?? '')" :required="field.required" maxlength="2000" :disabled="disabled" rows="3" @input="input(field.key, $event)" />
      <select v-else-if="field.type === 'SELECT'" :value="values[field.key] ?? ''" :required="field.required" :disabled="disabled" @change="input(field.key, $event)">
        <option value="">请选择</option><option v-for="option in field.options" :key="option" :value="option">{{ option }}</option>
      </select>
      <input v-else-if="field.type === 'CHECKBOX'" type="checkbox" :checked="values[field.key] === true" :required="field.required" :disabled="disabled" @change="values[field.key] = ($event.target as HTMLInputElement).checked" />
      <input v-else :type="field.type === 'NUMBER' ? 'number' : field.type === 'DATE' ? 'date' : 'text'" :step="field.type === 'NUMBER' ? 'any' : undefined" :value="String(values[field.key] ?? '')" :required="field.required" maxlength="500" :disabled="disabled" @input="input(field.key, $event)" />
    </label>
  </div>
</template>

<style scoped>
.form-fields { display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px; }
label { display:grid;gap:9px;font-size:13px;min-width:0; }.wide { grid-column:1/-1; }em { color:var(--danger,#b74945);font-style:normal; }
input,select,textarea { width:100%;min-height:42px;padding:10px 12px;border:1px solid var(--line,#dce4dd);border-radius:9px;background:var(--surface,#fff);color:inherit;font:inherit; }
input:focus,select:focus,textarea:focus { outline:2px solid #73a78a;outline-offset:2px; }.check { display:flex;align-items:center;flex-direction:row-reverse;justify-content:flex-end;gap:10px; }.check input { width:18px;min-height:18px; }
@media(max-width:560px) { .form-fields { grid-template-columns:1fr; } }
</style>
