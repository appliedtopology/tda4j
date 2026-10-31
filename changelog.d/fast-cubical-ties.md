### Changed

- `FastCubicalHomologyEngine` (and so `Persistence` on an image) is much faster at writing representatives for an image
  with many equal maxima, such as a tiling or a distance image of a pattern: of two components born at the same value
  the smaller dies, so the regions it walks to write cycles stay small. The bars are the same; a bar whose two
  components were born at the same value may now have the other component's boundary as its cycle (still a valid cycle,
  born and dying with the bar). A Truchet pattern of 1024 x 1024 pixels in a frame took 87 s and takes 4 s (#290).
