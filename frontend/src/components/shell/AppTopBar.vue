<script setup lang="ts">
interface RailVisibility {
  left: boolean;
  right: boolean;
  bottom: boolean;
}

const props = defineProps<{
  projectId: string;
  projectName: string;
  theme: string;
  menuFor: string | null;
  railVisible: RailVisibility;
}>();

const emit = defineEmits<{
  "update:menuFor": [value: string | null];
  "change-project": [];
  "goto-view": [view: string];
  "run-extract": [force?: boolean];
  "open-settings": [];
  "reset-layout": [];
  "toggle-left": [];
  "toggle-right": [];
  "toggle-bottom": [];
  "open-palette": [];
  "toggle-theme": [];
}>();

function toggleMenu(menu: string): void {
  emit("update:menuFor", props.menuFor === menu ? null : menu);
}

function closeMenu(): void {
  emit("update:menuFor", null);
}

function changeProject(): void {
  closeMenu();
  emit("change-project");
}

function gotoView(view: string): void {
  closeMenu();
  emit("goto-view", view);
}

function runExtract(force = false): void {
  closeMenu();
  emit("run-extract", force);
}

function openSettings(): void {
  closeMenu();
  emit("open-settings");
}

function resetLayout(): void {
  closeMenu();
  emit("reset-layout");
}
</script>

<template>
  <div class="topbar ide">
    <div class="tb-group tb-left">
      <div class="top-menu-wrap">
        <button
          class="ghost icon-btn"
          @click="toggleMenu('burger')"
          title="Меню"
        >
          <svg class="icon" viewBox="0 0 24 24">
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>
        <div v-if="menuFor === 'burger'" class="dz-menu top-menu left">
          <div class="dz-menu-h">Проект</div>
          <div class="dz-menu-i" @click="changeProject">Сменить проект…</div>
          <div class="dz-menu-i" @click="gotoView('summary')">
            Сводка перевода
          </div>
          <div class="dz-menu-i" @click="runExtract()">
            Обновить данные игры
          </div>
          <div class="dz-menu-i" @click="openSettings">Настройки…</div>
          <div class="dz-menu-i dim" @click="runExtract(true)">
            Обновить всё принудительно
          </div>
          <div class="dz-menu-i dim" @click="resetLayout">
            Сбросить раскладку
          </div>
        </div>
      </div>
      <div class="brand">
        <img class="logo" src="/img/yuki-icon.png" alt="Yuki" />
        <span>Harmonia Suite</span>
      </div>
      <button
        class="proj-pill"
        @click="emit('change-project')"
        :title="projectId"
      >
        <span class="proj-name">{{ projectName || projectId || "—" }}</span
        ><svg class="icon" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6" /></svg>
      </button>
    </div>
    <div class="toolbar tb-group tb-right">
      <div class="top-menu-wrap">
        <button
          class="ghost icon-btn"
          @click="toggleMenu('view')"
          title="Вид: панели и раскладка"
        >
          <svg class="icon" viewBox="0 0 24 24">
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <path d="M9 4v16M15 4v16" />
          </svg>
        </button>
        <div v-if="menuFor === 'view'" class="dz-menu top-menu">
          <div class="dz-menu-h">Панели</div>
          <div
            class="dz-menu-i"
            :class="{ off: !railVisible.left }"
            @click="
              emit('toggle-left');
              closeMenu();
            "
          >
            Левая
          </div>
          <div
            class="dz-menu-i"
            :class="{ off: !railVisible.right }"
            @click="
              emit('toggle-right');
              closeMenu();
            "
          >
            Правая
          </div>
          <div
            class="dz-menu-i"
            :class="{ off: !railVisible.bottom }"
            @click="
              emit('toggle-bottom');
              closeMenu();
            "
          >
            Нижняя
          </div>
          <div class="dz-menu-i dim" @click="resetLayout">
            Сбросить раскладку
          </div>
        </div>
      </div>
      <button
        class="ghost icon-btn"
        @click="emit('open-palette')"
        title="Быстрый переход (Ctrl+K)"
      >
        <svg class="icon" viewBox="0 0 24 24">
          <circle cx="11" cy="11" r="7" />
          <path d="M21 21l-4.3-4.3" />
        </svg>
      </button>
      <button
        class="theme-btn icon-btn"
        @click="emit('toggle-theme')"
        :title="theme === 'dark' ? 'Светлая тема' : 'Тёмная тема'"
      >
        <svg class="icon" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="4.5" />
          <path
            d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
          />
        </svg>
      </button>
      <button
        class="ghost icon-btn"
        @click="emit('change-project')"
        title="Сменить проект"
      >
        <svg class="icon" viewBox="0 0 24 24">
          <path
            d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"
          />
        </svg>
      </button>
    </div>
  </div>
</template>
