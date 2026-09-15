import { createApp } from "vue";
import App from "./App.vue";
import "./assets/app.css";
import "./theme";

const app = createApp(App);

app.config.errorHandler = (error, _instance, info) => {
  console.error("[vue]", info, error);
};

window.addEventListener("unhandledrejection", (event) => {
  console.error("[promise]", event.reason);
});

app.mount("#app");
