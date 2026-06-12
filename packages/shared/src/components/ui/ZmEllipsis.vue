<template>
  <span class="zm-ellipsis"><slot /></span>
</template>

<style scoped>
.zm-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
