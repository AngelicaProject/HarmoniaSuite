import { createApp } from "vue";
import App from "./App.vue";
import "./assets/app.css";

const app = createApp(App);

app.config.errorHandler = (error, _instance, info) => {
  console.error("[vue]", info, error);
  try {
    const box = document.createElement("div");
    box.className = "toast";
    box.textContent = `Ошибка интерфейса (${info || "render"}): ${error instanceof Error ? error.message : String(error)}`;
    document.body.appendChild(box);
    setTimeout(() => box.remove(), 8000);
  } catch (fallbackError) {
    console.error("[vue-fallback]", fallbackError);
  }
};

window.addEventListener("unhandledrejection", (event) => {
  console.error("[promise]", event.reason);
});

app.mount("#app");
