<script setup lang="ts" generic="T extends string | number">
defineProps<{ options: { value: T; label: string; hint?: string }[]; label: string; name: string }>()
const model = defineModel<T>({ required: true })
</script>

<template>
  <div class="seg-wrap">
    <span class="label" :id="`${name}-label`">{{ label }}</span>
    <div class="seg" role="radiogroup" :aria-labelledby="`${name}-label`">
      <label v-for="o in options" :key="String(o.value)" class="opt" :class="{ on: model === o.value }" :title="o.hint">
        <input v-model="model" class="sr-only" type="radio" :name="name" :value="o.value" />
        <span>{{ o.label }}</span>
      </label>
    </div>
  </div>
</template>

<style scoped>
.seg-wrap {
  display: grid;
  gap: 6px;
}
.label {
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.seg {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.opt {
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  padding: 0 14px;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-pill);
  background: var(--surface);
  font-size: var(--fs-sm);
  cursor: pointer;
  transition: background var(--t-fast), border-color var(--t-fast), color var(--t-fast);
}
.opt:hover {
  border-color: var(--ink-3);
}
.opt.on {
  background: var(--ink);
  border-color: var(--ink);
  color: var(--cream);
}
.opt:has(input:focus-visible) {
  outline: 2px solid var(--focus);
  outline-offset: 2px;
}
</style>
