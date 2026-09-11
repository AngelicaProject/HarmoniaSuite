import { nextTick } from "vue";
import { afterEach, describe, expect, it } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import Dropdown from "./Dropdown.vue";

describe("Dropdown", () => {
  let wrapper: VueWrapper | null = null;

  afterEach(() => {
    wrapper?.unmount();
    wrapper = null;
  });

  it("exposes listbox semantics and focuses the highlighted option", async () => {
    wrapper = mount(Dropdown, {
      props: {
        modelValue: "one",
        options: [
          { value: "one", label: "One" },
          { value: "two", label: "Two" },
        ],
      },
      attachTo: document.body,
    });

    const trigger = wrapper.find("button");
    expect(trigger.attributes("aria-haspopup")).toBe("listbox");
    expect(trigger.attributes("aria-expanded")).toBe("false");
    expect(trigger.attributes("aria-activedescendant")).toBeUndefined();

    await trigger.trigger("click");

    expect(trigger.attributes("aria-expanded")).toBe("true");
    expect(wrapper.find('[role="listbox"]').exists()).toBe(true);
    expect(
      wrapper.findAll('[role="option"]')[0].attributes("aria-selected"),
    ).toBe("true");
    expect(document.activeElement).toBe(
      wrapper.findAll('[role="option"]')[0].element,
    );
  });

  it("supports ArrowDown, ArrowUp, Enter, Escape, and restores trigger focus", async () => {
    wrapper = mount(Dropdown, {
      props: {
        modelValue: "one",
        options: [
          { value: "one", label: "One" },
          { value: "two", label: "Two" },
        ],
      },
      attachTo: document.body,
    });

    const trigger = wrapper.find("button");
    await trigger.trigger("keydown", { key: "ArrowDown" });
    let options = wrapper.findAll('[role="option"]');
    expect(document.activeElement).toBe(options[0].element);

    await options[0].trigger("keydown", { key: "ArrowDown" });
    options = wrapper.findAll('[role="option"]');
    expect(document.activeElement).toBe(options[1].element);

    await options[1].trigger("keydown", { key: "ArrowUp" });
    options = wrapper.findAll('[role="option"]');
    expect(document.activeElement).toBe(options[0].element);

    await options[0].trigger("keydown", { key: "Enter" });
    expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual(["one"]);
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false);
    expect(document.activeElement).toBe(trigger.element);

    await trigger.trigger("click");
    options = wrapper.findAll('[role="option"]');
    await options[0].trigger("keydown", { key: "Escape" });
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false);
    expect(document.activeElement).toBe(trigger.element);
  });

  it("closes on Tab without preventing the native focus transition", async () => {
    wrapper = mount(Dropdown, {
      props: {
        modelValue: "one",
        options: [
          { value: "one", label: "One" },
          { value: "two", label: "Two" },
        ],
      },
      attachTo: document.body,
    });

    const trigger = wrapper.find("button");
    await trigger.trigger("click");
    const option = wrapper.find('[role="option"]');
    (option.element as HTMLElement).focus();

    const event = new KeyboardEvent("keydown", {
      key: "Tab",
      bubbles: true,
      cancelable: true,
    });
    option.element.dispatchEvent(event);
    await nextTick();

    expect(event.defaultPrevented).toBe(false);
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false);
    expect(document.activeElement).not.toBe(trigger.element);
  });
});
