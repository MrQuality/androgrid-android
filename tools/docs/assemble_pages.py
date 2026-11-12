#!/usr/bin/env python3
"""
Assembles the complete GitHub Pages static distribution for androgrid-android.
Traces to: [NFR-DOC-001], [REQ-PUB-001]

Builds a modern, engaging documentation portal embedding the Onboarding Guide,
Client Architecture, and Dokka KDoc API references.
"""

import os
import re
import shutil
import sys
from pathlib import Path


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{title} - AndroGrid Android Worker</title>
    <link rel="stylesheet" href="{base_path}style.css">
    <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
    <script>
        document.addEventListener("DOMContentLoaded", function() {{
            mermaid.initialize({{ startOnLoad: true, theme: 'dark', securityLevel: 'loose' }});
        }});
    </script>
</head>
<body>
    <header class="navbar">
        <div class="nav-container">
            <a href="{base_path}index.html" class="nav-brand">
                <span class="pulse-dot"></span>
                AndroGrid Android Worker
            </a>
            <nav class="nav-links">
                <a href="{base_path}index.html">Overview</a>
                <a href="{base_path}worker-guide.html">Onboarding Guide</a>
                <a href="{base_path}architecture.html">Architecture</a>
                <a href="{base_path}dokka/index.html">Dokka KDoc API</a>
                <a href="https://mrquality.github.io/androgrid-backend/" target="_blank" class="nav-ext">Central Portal &nearr;</a>
            </nav>
        </div>
    </header>
    <main class="content-container">
        {content}
    </main>
    <footer class="footer">
        <p>&copy; 2026 AndroGrid Authors. Built with ISO/IEC/IEEE 29148 Traceability.</p>
    </footer>
</body>
</html>
"""

CSS_STYLES = """/* AndroGrid Android Worker Documentation Theme */
:root {
    --bg-dark: #0b0f19;
    --bg-card: rgba(17, 24, 39, 0.75);
    --border-color: rgba(56, 189, 248, 0.2);
    --text-primary: #f8fafc;
    --text-secondary: #94a3b8;
    --cyan: #38bdf8;
    --violet: #818cf8;
    --emerald: #34d399;
    --amber: #fbbf24;
}

* { box-sizing: border-box; margin: 0; padding: 0; }

body {
    background-color: var(--bg-dark);
    color: var(--text-primary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    line-height: 1.6;
}

.navbar {
    background: rgba(11, 15, 25, 0.85);
    backdrop-filter: blur(12px);
    border-bottom: 1px solid var(--border-color);
    position: sticky;
    top: 0;
    z-index: 100;
}

.nav-container {
    max-width: 1200px;
    margin: 0 auto;
    padding: 0.85rem 1.5rem;
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.nav-brand {
    font-size: 1.15rem;
    font-weight: 700;
    color: var(--cyan);
    text-decoration: none;
    display: flex;
    align-items: center;
    gap: 0.6rem;
}

.pulse-dot {
    width: 0.65rem;
    height: 0.65rem;
    border-radius: 50%;
    background-color: var(--emerald);
    box-shadow: 0 0 0 rgba(52, 211, 153, 0.6);
    animation: pulse 1.8s infinite;
}

@keyframes pulse {
    0% { box-shadow: 0 0 0 0 rgba(52, 211, 153, 0.7); }
    70% { box-shadow: 0 0 0 8px rgba(52, 211, 153, 0); }
    100% { box-shadow: 0 0 0 0 rgba(52, 211, 153, 0); }
}

.nav-links {
    display: flex;
    gap: 1.25rem;
}

.nav-links a {
    color: var(--text-secondary);
    text-decoration: none;
    font-size: 0.92rem;
    font-weight: 500;
    transition: color 0.15s ease;
}

.nav-links a:hover {
    color: var(--cyan);
}

.nav-ext {
    color: var(--violet) !important;
}

.content-container {
    max-width: 1000px;
    margin: 2.5rem auto;
    padding: 0 1.5rem;
}

h1 {
    background: linear-gradient(135deg, #38bdf8 0%, #818cf8 50%, #c084fc 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    font-size: 2.25rem;
    font-weight: 800;
    margin-bottom: 1.5rem;
    border-bottom: 2px solid rgba(56, 189, 248, 0.25);
    padding-bottom: 0.5rem;
}

h2 {
    color: var(--cyan);
    font-size: 1.5rem;
    margin: 2rem 0 1rem;
    border-bottom: 1px solid rgba(56, 189, 248, 0.15);
    padding-bottom: 0.35rem;
}

h3 {
    color: #93c5fd;
    font-size: 1.2rem;
    margin: 1.5rem 0 0.75rem;
}

p, ul, ol {
    margin-bottom: 1.2rem;
    color: #cbd5e1;
}

ul, ol {
    padding-left: 1.5rem;
}

li {
    margin-bottom: 0.4rem;
}

a {
    color: var(--cyan);
    text-decoration: none;
}

a:hover {
    text-decoration: underline;
}

pre {
    background: #0f172a;
    border: 1px solid var(--border-color);
    border-radius: 8px;
    padding: 1rem 1.25rem;
    overflow-x: auto;
    margin: 1.2rem 0;
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.35);
}

code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 0.9em;
    color: #38bdf8;
}

pre code {
    color: #e2e8f0;
}

table {
    width: 100%;
    border-collapse: collapse;
    margin: 1.5rem 0;
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 8px;
    overflow: hidden;
}

th {
    background: rgba(30, 41, 59, 0.9);
    color: var(--cyan);
    font-size: 0.8rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    padding: 0.85rem 1rem;
    text-align: left;
    border-bottom: 2px solid rgba(56, 189, 248, 0.3);
}

td {
    padding: 0.85rem 1rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

tr:hover td {
    background: rgba(56, 189, 248, 0.06);
}

.hero-banner {
    text-align: center;
    padding: 3rem 1rem;
    margin-bottom: 2.5rem;
    background: radial-gradient(circle at center, rgba(56, 189, 248, 0.12) 0%, transparent 70%);
    border-radius: 16px;
    border: 1px solid var(--border-color);
}

.hero-title {
    font-size: 2.75rem;
    font-weight: 800;
    margin-bottom: 1rem;
}

.hero-subtitle {
    font-size: 1.2rem;
    color: var(--text-secondary);
    max-width: 700px;
    margin: 0 auto 1.75rem;
}

.cards-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 1.5rem;
    margin: 2.5rem 0;
}

.card {
    background: var(--bg-card);
    border: 1px solid var(--border-color);
    border-radius: 12px;
    padding: 1.75rem;
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
    transition: transform 0.2s ease, border-color 0.2s ease;
    text-decoration: none;
    color: inherit;
    display: flex;
    flex-direction: column;
}

.card:hover {
    transform: translateY(-4px);
    border-color: var(--cyan);
    text-decoration: none;
}

.card-title {
    font-size: 1.25rem;
    font-weight: 700;
    color: var(--cyan);
    margin-bottom: 0.6rem;
}

.card-desc {
    color: var(--text-secondary);
    font-size: 0.95rem;
    flex-grow: 1;
}

.card-cta {
    margin-top: 1rem;
    font-size: 0.9rem;
    font-weight: 600;
    color: var(--violet);
}

.doc-image {
    margin: 2rem auto;
    text-align: center;
    max-width: 100%;
}

.doc-image img {
    max-width: 100%;
    height: auto;
    border-radius: 8px;
    border: 1px solid var(--border-color);
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.4);
    display: block;
    margin: 0 auto;
}

.doc-image figcaption {
    margin-top: 0.6rem;
    font-size: 0.85rem;
    color: var(--text-secondary);
    font-style: italic;
}

.footer {
    text-align: center;
    padding: 2.5rem 1rem;
    color: var(--text-secondary);
    font-size: 0.85rem;
    border-top: 1px solid rgba(255, 255, 255, 0.05);
    margin-top: 4rem;
}
"""

LANDING_BODY = """
<div class="hero-banner">
    <h1 class="hero-title">AndroGrid Android Worker</h1>
    <p class="hero-subtitle">
        High-concurrency mobile edge computing client for Android devices. Harness idle compute capacity safely with WebAssembly sandboxing, Mutual TLS 1.3, and baseband radio sleep guards.
    </p>
    <div style="display: flex; gap: 1rem; justify-content: center;">
        <a href="worker-guide.html" style="padding: 0.6rem 1.2rem; background: #0284c7; color: white; border-radius: 8px; font-weight: 600; text-decoration: none;">Onboarding Guide</a>
        <a href="dokka/index.html" style="padding: 0.6rem 1.2rem; background: #334155; color: white; border-radius: 8px; font-weight: 600; text-decoration: none;">Dokka API Reference</a>
    </div>
</div>

<div class="cards-grid">
    <a href="worker-guide.html" class="card">
        <h3 class="card-title">Worker Onboarding Guide</h3>
        <p class="card-desc">
            Complete device onboarding walkthrough: ADB sideloading flags, OpenSSL ECDSA P-256 certificate generation, KeyStore PKCS#12 imports, Podman connectivity, and OEM task killer bypasses.
        </p>
        <span class="card-cta">Read Onboarding Guide &rarr;</span>
    </a>
    <a href="architecture.html" class="card">
        <h3 class="card-title">Client Architecture & State Machine</h3>
        <p class="card-desc">
            Deep dive into the Section 6.1 Connection State Matrix, WorkManager spot scheduling vs Wi-Fi declarative loops, typed foreground services, and Chicory WASM sandboxing.
        </p>
        <span class="card-cta">Explore Architecture &rarr;</span>
    </a>
    <a href="dokka/index.html" class="card">
        <h3 class="card-title">Dokka KDoc API Reference</h3>
        <p class="card-desc">
            Interactive, navigable Kotlin API documentation for all packages, service classes, WorkManager workers, and protobuf bindings.
        </p>
        <span class="card-cta">Browse Kotlin KDoc API &rarr;</span>
    </a>
    <a href="https://mrquality.github.io/androgrid-backend/" target="_blank" class="card">
        <h3 class="card-title">Central Cluster Portal</h3>
        <p class="card-desc">
            Access the master AndroGrid documentation hub for host orchestrator deployment, operator CLI reference, Cloudflare Anycast ingress, and Protobuf contracts.
        </p>
        <span class="card-cta">Visit Central Portal &nearr;</span>
    </a>
</div>
"""


def markdown_to_html(md_text: str) -> str:
    """Lightweight markdown to HTML converter for docs."""
    lines = md_text.splitlines()
    html_lines = []
    in_code = False
    code_lang = ""
    in_table = False
    in_list = False

    for line in lines:
        stripped = line.strip()

        # Handle code blocks
        if stripped.startswith("```"):
            if in_code:
                in_code = False
                if code_lang == "mermaid":
                    html_lines.append("</div>")
                else:
                    html_lines.append("</code></pre>")
            else:
                in_code = True
                code_lang = stripped[3:].strip()
                if code_lang == "mermaid":
                    html_lines.append('<div class="mermaid">')
                else:
                    html_lines.append(f'<pre><code class="language-{code_lang}">')
            continue

        if in_code:
            if code_lang == "mermaid":
                html_lines.append(line)
            else:
                escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                html_lines.append(escaped)
            continue

        # Handle tables
        if stripped.startswith("|") and stripped.endswith("|"):
            if not in_table:
                in_table = True
                html_lines.append("<table>")
            if ":---" in stripped or "---:" in stripped or "---" in stripped:
                continue
            cells = [c.strip() for c in stripped.split("|")[1:-1]]
            tag = "th" if "<thead>" not in "".join(html_lines[-3:]) and len(html_lines) < 20 else "td"
            row = "".join([f"<{tag}>{c}</{tag}>" for c in cells])
            html_lines.append(f"<tr>{row}</tr>")
            continue
        elif in_table:
            in_table = False
            html_lines.append("</table>")

        # Handle unordered lists
        if stripped.startswith("- ") or stripped.startswith("* "):
            if not in_list:
                in_list = True
                html_lines.append("<ul>")
            html_lines.append(f"<li>{stripped[2:]}</li>")
            continue
        elif in_list and not stripped.startswith("- ") and not stripped.startswith("* "):
            in_list = False
            html_lines.append("</ul>")

        # Headers
        if stripped.startswith("### "):
            html_lines.append(f"<h3>{stripped[4:]}</h3>")
        elif stripped.startswith("## "):
            html_lines.append(f"<h2>{stripped[3:]}</h2>")
        elif stripped.startswith("# "):
            html_lines.append(f"<h1>{stripped[2:]}</h1>")
        elif stripped == "---":
            html_lines.append("<hr style='border: none; border-top: 1px solid rgba(255,255,255,0.1); margin: 2rem 0;'>")
        elif re.match(r"^!\[(.*?)\]\((.*?)\)$", stripped):
            img_match = re.match(r"^!\[(.*?)\]\((.*?)\)$", stripped)
            alt, src = img_match.group(1), img_match.group(2)
            if alt:
                html_lines.append(f'<figure class="doc-image"><img src="{src}" alt="{alt}"><figcaption>{alt}</figcaption></figure>')
            else:
                html_lines.append(f'<figure class="doc-image"><img src="{src}" alt=""></figure>')
        elif stripped:
            html_lines.append(f"<p>{stripped}</p>")

    if in_table:
        html_lines.append("</table>")
    if in_list:
        html_lines.append("</ul>")

    content = "\n".join(html_lines)
    # Convert inline images
    def inline_img_replace(match):
        alt_text = match.group(1)
        src_url = match.group(2)
        if alt_text:
            return f'<figure class="doc-image"><img src="{src_url}" alt="{alt_text}"><figcaption>{alt_text}</figcaption></figure>'
        return f'<figure class="doc-image"><img src="{src_url}" alt=""></figure>'

    content = re.sub(r"!\[(.*?)\]\((.*?)\)", inline_img_replace, content)
    # Convert inline code and strong
    def inline_code_replace(match):
        code_text = match.group(1).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("*", "&#42;")
        return f"<code>{code_text}</code>"

    content = re.sub(r"`([^`\n]+)`", inline_code_replace, content)
    content = re.sub(r"\*\*([^\*\n]+)\*\*", r"<strong>\1</strong>", content)
    content = re.sub(r"(?<!!)\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', content)
    # Rewrite relative ADR and traceability markdown links to HTML for browser navigation
    content = re.sub(r'href="adr/(ADR-[^"]+)\.md"', r'href="adr/\1.html"', content)
    content = re.sub(r'href="(ADR-[^"]+)\.md"', r'href="\1.html"', content)
    content = re.sub(r'href="traceability_matrix\.md"', r'href="traceability_matrix.html"', content)
    return content


def assemble_site(project_root: Path, output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)

    # 1. Write style.css
    (output_dir / "style.css").write_text(CSS_STYLES, encoding="utf-8")

    # 2. Write index.html
    landing_html = HTML_TEMPLATE.format(base_path="", title="Overview", content=LANDING_BODY)
    (output_dir / "index.html").write_text(landing_html, encoding="utf-8")

    # 3. Convert docs/worker-guide.md
    worker_guide_md = project_root / "docs" / "worker-guide.md"
    if worker_guide_md.exists():
        raw_md = worker_guide_md.read_text(encoding="utf-8")
        body_html = markdown_to_html(raw_md)
        page_html = HTML_TEMPLATE.format(base_path="", title="Onboarding Guide", content=body_html)
        (output_dir / "worker-guide.html").write_text(page_html, encoding="utf-8")
        print("[+] Generated worker-guide.html")

    # 4. Convert docs/architecture.md
    arch_md = project_root / "docs" / "architecture.md"
    if arch_md.exists():
        raw_md = arch_md.read_text(encoding="utf-8")
        body_html = markdown_to_html(raw_md)
        page_html = HTML_TEMPLATE.format(base_path="", title="Architecture", content=body_html)
        (output_dir / "architecture.html").write_text(page_html, encoding="utf-8")
        print("[+] Generated architecture.html")

    # 4b. Convert docs/adr/*.md to site/adr/*.html and copy raw .md files
    adr_src_dir = project_root / "docs" / "adr"
    adr_dest_dir = output_dir / "adr"
    adr_dest_dir.mkdir(parents=True, exist_ok=True)
    if adr_src_dir.exists():
        for adr_path in sorted(adr_src_dir.glob("*.md")):
            raw_adr = adr_path.read_text(encoding="utf-8")
            body_html = markdown_to_html(raw_adr)
            page_html = HTML_TEMPLATE.format(base_path="../", title=adr_path.stem, content=body_html)
            (adr_dest_dir / f"{adr_path.stem}.html").write_text(page_html, encoding="utf-8")
            (adr_dest_dir / adr_path.name).write_text(raw_adr, encoding="utf-8")
            print(f"[+] Generated adr/{adr_path.stem}.html and {adr_path.name}")

    # 4c. Convert docs/traceability_matrix.md to site/traceability_matrix.html and copy raw .md
    matrix_md = project_root / "docs" / "traceability_matrix.md"
    if matrix_md.exists():
        raw_md = matrix_md.read_text(encoding="utf-8")
        body_html = markdown_to_html(raw_md)
        page_html = HTML_TEMPLATE.format(base_path="", title="Traceability Matrix", content=body_html)
        (output_dir / "traceability_matrix.html").write_text(page_html, encoding="utf-8")
        (output_dir / "traceability_matrix.md").write_text(raw_md, encoding="utf-8")
        print("[+] Generated traceability_matrix.html and copied traceability_matrix.md")

    # 5. Copy Dokka HTML output
    dokka_src = project_root / "app" / "build" / "dokka" / "html"
    dokka_dest = output_dir / "dokka"
    if dokka_src.exists():
        if dokka_dest.exists():
            shutil.rmtree(dokka_dest)
        shutil.copytree(dokka_src, dokka_dest)
        print(f"[+] Copied Dokka documentation from {dokka_src} to {dokka_dest}")
    else:
        # Create fallback Dokka placeholder if running offline before gradle execution
        dokka_dest.mkdir(parents=True, exist_ok=True)
        placeholder = """<!DOCTYPE html>
<html><head><meta http-equiv="refresh" content="0; url=../index.html"></head>
<body><p>Dokka documentation generated via CI workflow.</p></body></html>"""
        (dokka_dest / "index.html").write_text(placeholder, encoding="utf-8")
        print("[*] Created placeholder for dokka/index.html")

    # 6. Copy static assets (e.g. diagrams, illustrations) if present
    assets_src = project_root / "docs" / "assets"
    assets_dest = output_dir / "assets"
    if assets_src.exists():
        if assets_dest.exists():
            shutil.rmtree(assets_dest)
        shutil.copytree(assets_src, assets_dest)
        print(f"[+] Copied documentation assets from {assets_src} to {assets_dest}")
    else:
        assets_dest.mkdir(parents=True, exist_ok=True)
        print("[*] Created assets directory at site/assets")

    # 7. Sanitize typographic dashes
    forbidden = re.compile(r"[\u2013\u2014]")
    errors = 0
    for file in output_dir.rglob("*"):
        if file.is_file() and file.suffix in [".html", ".css", ".js"]:
            text = file.read_text(encoding="utf-8", errors="replace")
            if forbidden.search(text):
                text = text.replace("\u2013", "-").replace("\u2014", "-")
                file.write_text(text, encoding="utf-8")
                print(f"[!] Sanitized Unicode dashes in {file.name}")

    print(f"[SUCCESS] Documentation portal assembled at {output_dir}")


def main():
    project_root = Path(__file__).resolve().parent.parent.parent
    output_dir = project_root / "site"
    assemble_site(project_root, output_dir)


if __name__ == "__main__":
    main()
