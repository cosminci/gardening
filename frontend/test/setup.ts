import "@testing-library/jest-dom/vitest";
import { cleanup } from "@solidjs/testing-library";
import { afterEach } from "vitest";

process.env["TZ"] = "Europe/Bucharest";

afterEach(() => {
  cleanup();
});
