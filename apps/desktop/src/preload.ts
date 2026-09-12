import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("harmoniaUpdater", {
  checkForUpdates: () => ipcRenderer.invoke("harmonia:update:checkForUpdates"),
  installUpdate: () => ipcRenderer.invoke("harmonia:update:installUpdate"),
  getUpdateStatus: () => ipcRenderer.invoke("harmonia:update:getUpdateStatus"),
});
