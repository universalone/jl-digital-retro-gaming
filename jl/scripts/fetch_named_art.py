"""One-off: box art for games whose file names don't match the thumbnail server's titles."""
import importlib.util
import urllib.parse
import urllib.request
from pathlib import Path

spec = importlib.util.spec_from_file_location("jl_retro", r"D:\JL Digital Retro Gaming\jl_retro.pyw")
jl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jl)

SNES = "Nintendo - Super Nintendo Entertainment System"
# Game title (as shown in the library) -> thumbnail file name on thumbnails.libretro.com
PICKS = {
    "Super Earth Defense Force": "Earth Defense Force (USA).png",
    "Mickey-Minnie: The Great Circus Mystery 2": "Mickey to Minnie - Magical Adventure 2 (Japan).png",
    "Muscle Bomber": "Muscle Bomber - The Body Explosion (Japan).png",
    "Super Bomberman 5: Caravan Event Ban": "Super Bomberman 5 (Japan) (Caravan Event Ban).png",
    "Tiny Toons: Wild and Wacky Sports": "Tiny Toon Adventures - Wild _ Wacky Sports (Europe) (Rev 1).png",
    "Zelda no Densetsu": "Zelda no Densetsu - Kamigami no Triforce (Japan).png",
}

for g in jl.load_games():
    name = PICKS.get(g["title"])
    if not name or g["system"] != "snes" or g["art"].exists():
        continue
    url = "https://thumbnails.libretro.com/" + urllib.parse.quote(f"{SNES}/Named_Boxarts/{name}")
    data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "JLDigitalRetroGaming/1.0"}), timeout=60).read()
    if data.startswith(b"\x89PNG"):
        g["art"].parent.mkdir(parents=True, exist_ok=True)
        g["art"].write_bytes(data)
        print("OK ", g["title"])
