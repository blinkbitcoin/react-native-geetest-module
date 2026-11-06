const path = require("path")
const { getDefaultConfig, mergeConfig } = require("@react-native/metro-config")
const pak = require("../package.json")

const root = path.resolve(__dirname, "..")

const modules = Object.keys({
  ...pak.peerDependencies,
})

module.exports = mergeConfig(getDefaultConfig(__dirname), {
  projectRoot: __dirname,
  watchFolders: [root],

  // Load peer dependencies from the example so Metro sees only one copy.
  resolver: {
    blockList: modules.map(
      (name) =>
        new RegExp(
          `^${path
            .join(root, "node_modules", name)
            .replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}[/\\\\].*$`,
        ),
    ),
    extraNodeModules: modules.reduce((acc, name) => {
      acc[name] = path.join(__dirname, "node_modules", name)
      return acc
    }, {}),
  },

  transformer: {
    getTransformOptions: async () => ({
      transform: {
        experimentalImportSupport: false,
        inlineRequires: true,
      },
    }),
  },
})
