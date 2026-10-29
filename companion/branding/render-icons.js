// SPDX-License-Identifier: GPL-3.0-or-later
// Renders branding/jetpacs-logo.svg into the Companion's icons: the Android
// adaptive foreground and monochrome layers at every density and the
// desktop window icon.
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

// The themed icon (Android 13+) tints one alpha mask. The cream body stays
// solid and the purple E and flame centers cut out: alpha falls as blue
// outweighs green (purple), whatever the shade of cream, and is
// clipped to the drawing's own coverage.
const mask =
  '<filter id="jetpacs-monochrome" x="0" y="0" width="108" height="108" filterUnits="userSpaceOnUse">' +
  '<feColorMatrix type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 8 -8 0 1.6"/>' +
  '<feComposite in2="SourceAlpha" operator="in"/></filter>';
const fgOpen = foreground.indexOf(safe);
const monochrome =
  foreground.slice(0, fgOpen) + mask + '<g filter="url(#jetpacs-monochrome)">' +
  foreground.slice(fgOpen, foreground.lastIndexOf('</svg>')) + '</g></svg>';

const res = path.join(here, '../app/src/main/res');
for (const [density, px] of [['mdpi', 108], ['hdpi', 162], ['xhdpi', 216], ['xxhdpi', 324], ['xxxhdpi', 432]]) {
  render(foreground, path.join(res, `mipmap-${density}`, 'ic_launcher_foreground.png'), px);
  render(monochrome, path.join(res, `mipmap-${density}`, 'ic_launcher_monochrome.png'), px);
}
render(logo, path.join(here, '../desktop/src/main/resources/jetpacs-logo.png'), 256);
