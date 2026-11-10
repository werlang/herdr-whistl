# Design sources

- `icon-1024.png` — the delivered app icon art (1024×1024). The adaptive icon in
  `app/src/main/res/drawable/ic_launcher_foreground.xml` reproduces it as vector paths: the mark's geometry was
  measured from this file (body = upper half of a 850×854 ellipse, head = 262×288 tombstone, eye r=46 at (512,273),
  chevron stroke 45, underline stroke 36). Redraw here first, then re-measure; do not edit the vector by eye.
