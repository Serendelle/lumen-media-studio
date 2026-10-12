<script setup lang="ts">
import LandingNav from '@/components/landing/LandingNav.vue'
import SceneOpening from '@/components/landing/SceneOpening.vue'
import SceneCapabilities from '@/components/landing/SceneCapabilities.vue'
import SceneWorkspace from '@/components/landing/SceneWorkspace.vue'
</script>

<template>
  <div class="landing">
    <LandingNav />
    <main id="main">
      <SceneOpening />
      <SceneCapabilities />
      <SceneWorkspace />
    </main>
  </div>
</template>
