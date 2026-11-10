import os
import subprocess
from PIL import Image, ImageDraw

base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
svg_path = os.path.join(base_dir, "docs", "assets", "androgrid-logo.svg")
fg_svg_path = os.path.join(base_dir, "docs", "assets", "androgrid-logo-foreground.svg")

# 1. Create foreground-only SVG (transparent background)
with open(svg_path, "r", encoding="utf-8") as f:
    content = f.read()

fg_content = content.replace('<rect width="1024" height="1024" rx="48" fill="url(#androGridBg)" />', '')
with open(fg_svg_path, "w", encoding="utf-8") as f:
    f.write(fg_content)

# 2. Render foreground to PNG using resvg
fg_png_path = os.path.join(base_dir, "docs", "assets", "androgrid-logo-foreground-1024.png")
subprocess.run(["npx", "-y", "@resvg/resvg-js-cli", "--fit-width", "1024", fg_svg_path, fg_png_path], shell=True, check=True)

# 3. Generate mipmaps
master_full = Image.open(os.path.join(base_dir, "docs", "assets", "androgrid-logo-1024.png")).convert("RGBA")

densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

for folder, size in densities.items():
    out_dir = os.path.join(base_dir, "app", "src", "main", "res", folder)
    os.makedirs(out_dir, exist_ok=True)
    
    # Standard icon (squircle background)
    standard_icon = master_full.resize((size, size), Image.Resampling.LANCZOS)
    standard_icon.save(os.path.join(out_dir, "ic_launcher.png"))
    
    # Round icon (circular mask)
    round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size * 4, size * 4), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)
    
    round_icon.paste(standard_icon, (0, 0), mask)
    round_icon.save(os.path.join(out_dir, "ic_launcher_round.png"))
    print(f"Generated {folder} ({size}x{size})")

# 4. Generate in-app drawable (256x256)
drawable_dir = os.path.join(base_dir, "app", "src", "main", "res", "drawable")
os.makedirs(drawable_dir, exist_ok=True)
in_app_logo = master_full.resize((256, 256), Image.Resampling.LANCZOS)
in_app_logo.save(os.path.join(drawable_dir, "androgrid_logo.png"))
print("Generated in-app logo drawable (256x256)")
