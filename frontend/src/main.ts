import { createApp } from "vue";
import App from "./App.vue";
import "./assets/app.css";
import "./theme";
import { showNotification } from "./composables/useNotifications";

const app = createApp(App);

app.config.errorHandler = (error, _instance, info) => {
  console.error("[vue]", info, error);
  try {
    showNotification(
      "Ошибка интерфейса (" +
        (info || "render") +
        "): " +
        (error instanceof Error ? error.message : String(error)),
    );
  } catch (notificationError) {
    console.error("[vue-notification]", notificationError);
  }
};

window.addEventListener("unhandledrejection", (event) => {
  console.error("[promise]", event.reason);
});

app.mount("#app");
