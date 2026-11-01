"""One-off: JL Digital Arcade colors and 8-bit headers for the shared library page (web/index.html)."""
import sys

p = sys.argv[1]
s = open(p, encoding='utf-8').read()

# Old navy/orange palette -> neon arcade (indigo, hot pink, cyan, arcade yellow).
colors = {
    '--navy: #1B3A6B': '--navy: #2A1557',
    '--orange: #D2601A': '--orange: #FF2E97',
    '--orange-hi: #F07A2E': '--orange-hi: #FF5FB0',
    '--bg: #0B1322': '--bg: #140B2E',
    '--panel: #111D33': '--panel: #1C1040',
    '--panel-2: #172745': '--panel-2: #2A1557',
    '--line: #22355A': '--line: #3B2770',
    '--text: #F4F5F7': '--text: #FFFFFF',
    '--muted: #93A3BE': '--muted: #B9A7E8',
    '#1B3A6B55': '#7A2EFF44',
    '#D2601A22': '#00E5FF1F',
    '#D2601A33': '#FF2E9744',
    '#D2601A40': '#FF2E9740',
    '#D2601A44': '#FF2E9755',
    '#0B1322cc': '#140B2Ecc',
    '#0F203F': '#1A0B3D',
    '#0D1A31': '#170C36',
    '#3A5486': '#5B3FA0',
    '#8F3E0E': '#8A1052',
    '#050A14E6': '#0A0518E6',
    '#C9D3E4': '#DCD2F5',
}
for old, new in colors.items():
    if old not in s:
        sys.exit('not found: ' + old)
    s = s.replace(old, new)

# Bundled copy of the pixel font, so headers look right offline (TV boxes).
font_face = '''  @font-face { font-family: "Press Start 2P"; src: url("/PressStart2P.ttf") format("truetype"); font-display: swap; }
  :root {'''
s = s.replace('  :root {', font_face, 1)

headers = '''
  /* JL Digital Arcade: 8-bit headers and titles in arcade yellow with a neon glow */
  :root { --title: #FFD23F; --glow: 0 0 12px #FF2E9788, 3px 3px 0 #0008; }
  .wordmark .big, .view-head h1, .hero .title, .section-title, .rail h4, .hero .eyebrow {
    font-family: var(--pixel); font-weight: 400; letter-spacing: 0; color: var(--title); text-shadow: var(--glow);
  }
  .wordmark .big span { color: #00E5FF; }
  .view-head h1 { font-size: 22px; line-height: 1.4; }
  .hero .title { font-size: clamp(16px, 2.2vw, 30px); line-height: 1.35; }
  .section-title { font-size: 11px; }
  .rail h4 { font-size: 9px; color: #00E5FF; }
  .hero .eyebrow { font-size: 10px; color: #00E5FF; }
  .nav:focus, .chip-btn:focus, .chip-btn:hover { box-shadow: 0 0 12px #FF2E9766; }
</style>'''
s = s.replace('</style>', headers, 1)
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
