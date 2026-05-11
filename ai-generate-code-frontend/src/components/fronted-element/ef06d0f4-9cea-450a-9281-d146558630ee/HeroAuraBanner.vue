<script setup lang="ts">
interface NavItem {
  label: string;
  href?: string;
}

withDefaults(
  defineProps<{
    brandText?: string;
    titleText?: string;
    navItems?: NavItem[];
  }>(),
  { 
    brandText: "Crea",
    titleText: "UNEARTH",
    navItems: () => [
      { label: "Hire", href: "#" },
      { label: "Move", href: "#" },
    ],
  },
);
</script>

<template>
  <section class="hero-aura-shell" role="banner" aria-label="hero banner">
    <div class="hero-aura-blur hero-aura-blur--center" aria-hidden="true"></div>
    <div class="hero-aura-blur hero-aura-blur--base" aria-hidden="true"></div>

    <header class="hero-aura-topbar">
      <nav class="hero-aura-nav" aria-label="primary navigation">
        <a
          v-for="item in navItems"
          :key="item.label"
          class="hero-aura-link"
          :href="item.href || '#'"
        >
          {{ item.label }}
        </a>
      </nav>
      <div class="hero-aura-brand">{{ brandText }}</div>
    </header>

    <h1 class="hero-aura-title">{{ titleText }}</h1>
  </section>
</template>

<style scoped src="./hero-aura-banner.css"></style>
