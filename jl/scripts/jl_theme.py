"""One-off: JL Digital branding for the RetroArch sources (Ozone colors, header color, names)."""
import re
import sys

ROOT = sys.argv[1]


def patch(rel, pairs):
    p = ROOT + '/' + rel
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        if old not in s:
            sys.exit(rel + ': not found: ' + old[:70])
        s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8', newline='\n').write(s)


def quad(rgb, a_top=1.0, a_bottom=1.0):
    r, g, b = ((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255
    row = lambda a: '   %.4ff, %.4ff, %.4ff, %.2ff,\n' % (r, g, b, a)
    return row(a_top) + row(a_top) + row(a_bottom) + row(a_bottom)


BG, SIDEBAR, PINK, CYAN, YELLOW = 0x140B2E, 0x0D0720, 0xFF2E97, 0x00E5FF, 0xFFD23F

ozone = 'menu/drivers/ozone.c'
s = open(ROOT + '/' + ozone, encoding='utf-8').read()
# The "Basic Black" (default) theme becomes the JL Digital Arcade theme.
for name, body in [
    ('ozone_sidebar_gradient_top_dark', quad(SIDEBAR)),
    ('ozone_sidebar_gradient_bottom_dark', quad(SIDEBAR)),
    ('ozone_sidebar_background_dark', quad(SIDEBAR)),
    ('ozone_background_libretro_running_dark', quad(BG, 0.80, 1.0)),
]:
    s, n = re.subn(r'(static float %s\[16\]\s*=\s*\{\n).*?(\};)' % name, lambda m: m.group(1) + body + m.group(2), s, count=1, flags=re.S)
    assert n == 1, name
s = s.replace('static float ozone_border_0_dark[16]               = COLOR_HEX_TO_FLOAT(0x198AC6, 1.00);',
              'static float ozone_border_0_dark[16]               = COLOR_HEX_TO_FLOAT(0x%06X, 1.00);' % PINK)
s = s.replace('static float ozone_border_1_dark[16]               = COLOR_HEX_TO_FLOAT(0x89F1F2, 1.00);',
              'static float ozone_border_1_dark[16]               = COLOR_HEX_TO_FLOAT(0x%06X, 1.00);' % CYAN)
open(ROOT + '/' + ozone, 'w', encoding='utf-8', newline='\n').write(s)

patch(ozone, [
    ('''static ozone_theme_t ozone_theme_dark = {
   COLOR_HEX_TO_FLOAT(0x2D2D2D, 1.00f),                  /* background */
   ozone_background_libretro_running_dark,               /* background_libretro_running */

   COLOR_HEX_TO_FLOAT(0xFFFFFF, 1.00f),                  /* header_footer_separator */
   COLOR_HEX_TO_FLOAT(0xFFFFFF, 1.00f),                  /* text */
   COLOR_HEX_TO_FLOAT(0x212227, 1.00f),                  /* selection */
   COLOR_HEX_TO_FLOAT(0x2DA3CB, 1.00f),                  /* selection_border */
   COLOR_HEX_TO_FLOAT(0x51514F, 1.00f),                  /* entries_border */
   COLOR_HEX_TO_FLOAT(0xFFFFFF, 1.00f),                  /* entries_icon */
   COLOR_HEX_TO_FLOAT(0x00D9AE, 1.00f),                  /* text_selected */
   COLOR_HEX_TO_FLOAT(0x464646, 1.00f),                  /* message_background */

   0xFFFFFFFF,                                           /* text_rgba */
   0xFFFFFFFF,                                           /* text_sidebar_rgba */
   0x00FFC5FF,                                           /* text_selected_rgba */
   0x9F9FA1FF,                                           /* text_sublabel_rgba */''',
     '''/* JL Digital Arcade: neon pink/cyan on deep indigo (replaces "Basic Black") */
static ozone_theme_t ozone_theme_dark = {
   COLOR_HEX_TO_FLOAT(0x140B2E, 1.00f),                  /* background */
   ozone_background_libretro_running_dark,               /* background_libretro_running */

   COLOR_HEX_TO_FLOAT(0xFF2E97, 1.00f),                  /* header_footer_separator */
   COLOR_HEX_TO_FLOAT(0xFFFFFF, 1.00f),                  /* text */
   COLOR_HEX_TO_FLOAT(0x2A1557, 1.00f),                  /* selection */
   COLOR_HEX_TO_FLOAT(0x00E5FF, 1.00f),                  /* selection_border */
   COLOR_HEX_TO_FLOAT(0x3B2770, 1.00f),                  /* entries_border */
   COLOR_HEX_TO_FLOAT(0xFFFFFF, 1.00f),                  /* entries_icon */
   COLOR_HEX_TO_FLOAT(0x00E5FF, 1.00f),                  /* text_selected */
   COLOR_HEX_TO_FLOAT(0x2A1557, 1.00f),                  /* message_background */

   0xFFFFFFFF,                                           /* text_rgba */
   0xFFFFFFFF,                                           /* text_sidebar_rgba */
   0x00E5FFFF,                                           /* text_selected_rgba */
   0xB9A7E8FF,                                           /* text_sublabel_rgba */'''),
    # Header titles in arcade yellow on the JL theme.
    ('''         ticker_x_offset + header_margin + logo_icon_size + ozone->dimensions.entry_icon_padding,
         ozone->dimensions.header_height / 2 + ozone->fonts.title.line_centre_offset,
         video_width,
         video_height,
         ozone->theme->text_rgba,''',
     '''         ticker_x_offset + header_margin + logo_icon_size + ozone->dimensions.entry_icon_padding,
         ozone->dimensions.header_height / 2 + ozone->fonts.title.line_centre_offset,
         video_width,
         video_height,
         (ozone->theme == &ozone_theme_dark) ? 0xFFD23FFF : ozone->theme->text_rgba,'''),
])

# Ozone (with the JL theme) is the menu everywhere, phones included.
patch('configuration.c', [
    ('#elif defined(HAVE_MATERIALUI) && defined(RARCH_MOBILE)\nstatic const enum menu_driver_enum MENU_DEFAULT_DRIVER = MENU_MATERIALUI;',
     '#elif defined(HAVE_MATERIALUI) && defined(RARCH_MOBILE) && !defined(ANDROID)\nstatic const enum menu_driver_enum MENU_DEFAULT_DRIVER = MENU_MATERIALUI;'),
])

# English menu text: the emulator is "JL Retro".
p = ROOT + '/intl/msg_hash_us.h'
s = open(p, encoding='utf-8').read()
s = s.replace('"Basic Black"', '"JL Digital Arcade"', 1)
s = re.sub(r'"(?:[^"\\]|\\.)*"', lambda m: m.group(0).replace('RetroArch', 'JL Retro'), s)
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
