<template>
  <div class="reference-footer-backdrop" aria-hidden="true">
    <div class="reference-footer-backdrop__desktop-layer" />
  </div>
</template>

<style scoped>
.reference-footer-backdrop {
  position: absolute;
  z-index: 1;
  right: -120px;
  bottom: -80px;
  width: min(68vw, 960px);
  height: 420px;
  pointer-events: none;
  opacity: 0.24;
}

.reference-footer-backdrop__desktop-layer {
  width: 100%;
  height: 100%;
  background-image: url('https://itscraft.com/wp-content/themes/craft-2025/public/footer-bg-desktop.avif');
  background-repeat: no-repeat;
  background-position: 50% 100%;
  background-size: contain;
}

@media (max-width: 768px) {
  .reference-footer-backdrop {
    width: 90vw;
    right: -180px;
    bottom: -140px;
    opacity: 0.18;
  }
}
</style>
