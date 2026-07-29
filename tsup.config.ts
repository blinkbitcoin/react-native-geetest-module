import { defineConfig } from "tsup"

export default defineConfig({
  entry: ["src/index.tsx"],
  outDir: "dist",
  format: ["cjs", "esm"],
  dts: true,
  sourcemap: false,
  clean: true,
  minify: false,
  skipNodeModulesBundle: true,
  target: "es2018",
  tsconfig: "tsconfig.build.json",
  external: ["react", "react-native"],
})
