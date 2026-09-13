import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

/** Directory containing the compiled desktop runtime modules. */
export const moduleDir = dirname(fileURLToPath(import.meta.url));
