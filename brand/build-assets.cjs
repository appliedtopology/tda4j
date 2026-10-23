// Copies the brand masters into _assets/images/ and renders their PNG sizes.
//
//   NODE_PATH=<dir holding node_modules/playwright> node brand/build-assets.cjs
//
// Playwright finds its Chromium through PLAYWRIGHT_BROWSERS_PATH; nothing else is needed. Light icons sit on white,
// dark icons on the docs site's dark surface (#161b1e): the PNGs are opaque because apple-touch-icon and the web
// manifest icons must be.
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const root = path.resolve(__dirname, "..");
const out = path.join(root, "_assets", "images");
const WHITE = "#ffffff";
const DARK = "#161b1e";

// master in brand/ -> file in _assets/images/
const copies = {
  "icon.svg": "header-icon.svg",
  "icon-dark.svg": "header-icon-dark.svg",
  "favicon.svg": "favicon.svg",
  "favicon-dark.svg": "favicon-dark.svg",
  "large-logo.svg": "large-logo.svg",
  "large-logo-dark.svg": "large-logo-dark.svg"
};

// PNG prefix -> [source master, background]
const pngs = {
  "header-icon": ["icon.svg", WHITE],
  "header-icon-dark": ["icon-dark.svg", DARK],
  favicon: ["favicon.svg", WHITE],
  "favicon-dark": ["favicon-dark.svg", DARK]
};
const sizes = [16, 32, 48, 64, 96, 128, 180, 192, 256, 512];

(async () => {
  for (const [from, to] of Object.entries(copies)) fs.copyFileSync(path.join(__dirname, from), path.join(out, to));
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const [prefix, [source, bg]] of Object.entries(pngs)) {
    const svg = fs.readFileSync(path.join(__dirname, source)).toString("base64");
    for (const size of sizes) {
      await page.setViewportSize({ width: size, height: size });
      await page.setContent(
        `<body style="margin:0;background:${bg}"><img width="${size}" height="${size}" style="display:block" src="data:image/svg+xml;base64,${svg}"></body>`
      );
      await page.screenshot({ path: path.join(out, `${prefix}-${size}.png`) });
    }
  }
  await browser.close();
})();
