import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";

import App from "./App.vue";

describe("transitional application shell", () => {
  it("mounts without legacy product workflow state", () => {
    const wrapper = mount(App);

    expect(wrapper.get("h1").text()).toBe("Harmonia");
    expect(wrapper.text()).toContain("canonical source pipeline");
  });
});
