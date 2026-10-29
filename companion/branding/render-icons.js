// SPDX-License-Identifier: GPL-3.0-or-later
// Renders branding/jetpacs-logo.svg into the Companion's icons: the Android
// adaptive foreground at every density and the desktop window icon.
//
//   cd companion/branding && npm install --no-save @resvg/resvg-js && node render-icons.js
//
// The foreground is scaled into the adaptive icon's 66 dp safe zone, around
// the drawing's own center, so no launcher mask clips the flames.
const { Resvg } = require('@resvg/resvg-js');
const fs = require('fs');
const path = require('path');

const here = __dirname;
const logo = fs.readFileSync(path.join(here, 'jetpacs-logo.svg'), 'utf8');

function render(svg, file, width) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const png = new Resvg(svg, { fitTo: { mode: 'width', value: width } }).render().asPng();
  fs.writeFileSync(file, png);
}

// The drawing's bounding-box center and the scale that fits it in a 33 dp radius.
const open = logo.match(/<svg\b[^>]*>/);
const safe = '<g transform="translate(54 54) scale(0.72) translate(-56.75 -53.25)">';
const close = logo.lastIndexOf('</svg>');
const foreground =
  logo.slice(0, open.index + open[0].length) + safe +
  logo.slice(open.index + open[0].length, close) + '</g>' + logo.slice(close);

const res = path.join(here, '../app/src/main/res');
for (const [density, px] of [['mdpi', 108], ['hdpi', 162], ['xhdpi', 216], ['xxhdpi', 324], ['xxxhdpi', 432]]) {
  render(foreground, path.join(res, `mipmap-${density}`, 'ic_launcher_foreground.png'), px);
}
render(logo, path.join(here, '../desktop/src/main/resources/jetpacs-logo.png'), 256);
