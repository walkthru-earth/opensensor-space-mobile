# Supporting peacock expression prompts

Generated with the built-in image generation tool. Every variant used `ui/designsystem/src/commonMain/composeResources/drawable/peacock.webp` as its identity reference and requested a true transparent background. Confused and working also used the initial generated portrait as an edit reference to bring the wing gesture beside the face. The selected square portraits were resized and exported to lossless 512 × 512 WebP with the generated alpha preserved (`exact=True`); no illustration was redrawn during export.

At 48 and 96 pixels, a light and dark background review confirmed the closed eye and wing tuck for sleeping, tilted head and shrugging wing for confused, and focused eyes with directional raised wing for working. Raw source PNGs and the review sheet are kept outside the repository in `/private/tmp/opensensor-peacock-source/`.

## confused

```text
Use case: identity-preserve
Asset type: expressive transparent peacock icon for a mobile app at 40–48dp.
References: image 1 is the ORIGINAL peacock identity and color/style reference; image 2 is the current confused portrait to refine. Preserve this one character exactly, including cobalt blue plumage, brown eyes with cream rings, pale grey hooked beak, five blue crest plumes, teal wing feathers with gold undersides, and emerald fan with gold-ringed blue eyes. Same polished soft 3D illustrated style.
Change: Make the CONFUSED pose more readable with creative feather/wing acting. Use an inquisitively tilted VERY LARGE head and asymmetric curious eyebrows. Raise one teal-and-gold wing RIGHT BESIDE ITS CHEEK in an obvious shrug, fanned feather tips upward, palm-like feather gesture facing up as if asking 'what happened?' No actual human hand or arm. The gesture must be adjacent to the face at face height, not at the bottom of the portrait. Eye expression uncertain and curious, not fearful.
Composition: A CLOSE-UP compact square chest-up portrait on real transparent background. Large face, eyes and beak occupy at least 55% of image width; a compact five-plume crest fits above without clipping. Only a short neck/upper chest and SMALL fragment of emerald tail behind the other shoulder. Main visual is FACE PLUS SHRUGGED WING, recognizable when reduced to 48 pixels. Entire head/crest/wing comfortably within frame with 6% margin. No feet or full body.
Avoid: text, question mark, emoji, symbol, tools, accessories, scenery, duplicate characters, changed identity, small head, long neck, dominant tail, bottom-only wing gestures, human anatomy, background.
```

The selected confused portrait received this final correction to retain all five crest plumes:

```text
Edit target is image 1, confused peacock transparent portrait. Supporting image 2 original character reference. In image 1 ADD A NEW FIFTH CREST PLUME at the outer-right edge of the crest. This is the sole edit. Current crest has four blue fan tips; retain all four and add a fifth distinct blue fan tip on a new thin blue stem growing from the same crown point. Added fifth plume should sit to the right and slightly below the existing rightmost plume, visibly separated. Total should be five: lower-left, upper-left, top-center, upper-right, new lower-right. Same blue/gold-tinted texture as four existing plumes. Do not change face, expression, shrugging wing, beak, brown eyes, cobalt neck, tail feathers, framing, rendering, colors or transparency. One square transparent image, no text, symbols or background.
```

## sleeping

```text
Use case: identity-preserve
Asset type: tiny mobile app peacock character icon readable at 40dp, transparent square portrait.
Input image: the provided peacock is the identity and rendering reference.
Primary request: Create one sleeping chest-up PORTRAIT of EXACTLY this same peacock character. Its large visible eye has a closed soft eyelid; tuck its head gently towards its shoulder, shoulders and wing relaxed. Express peaceful sleep through face and body alone.
Composition: One centered HEAD-DOMINANT chest-up peacock portrait, square canvas, actual transparent background. A VERY LARGE face occupies at least 45% of entire canvas width, with crest above filling remaining top. The eye, beak and brow must be large and clearly legible at tiny sizes. Include only a short blue neck and upper chest, hint of teal/gold wing and small partial emerald tail fan behind the shoulder. Do not show feet or full body. Keep all head and crest inside canvas with 6% margin; compact silhouette fills 88% of canvas. Face is the dominant focus. Bottom ends softly at upper chest, not long neck.
Preserve character identity: royal/cobalt blue feathers, brown eye anatomy and cream eye ring, pale grey hooked beak, five blue crest plumes, teal/scaled wing with gold undertones, emerald/teal tail fan with gold-ringed blue feather eyes. Preserve polished soft 3D illustrated rendering and clean vibrant palette.
Avoid: text, Zzz, emoji, symbols, scenery, accessories, pillows, hats, duplicate characters, clipped head/crest, white or colored background, full body, small head, changed character.
```

## working

```text
Use case: identity-preserve
Asset type: expressive transparent peacock icon for a mobile app at 40–48dp.
References: image 1 is the ORIGINAL peacock identity and color/style reference; image 2 is the current working portrait to refine. Preserve this one character exactly, including cobalt blue plumage, brown eyes with cream rings, pale grey hooked beak, EXACTLY FIVE separate blue crest plumes, teal wing feathers with gold undersides, and emerald fan with gold-ringed blue eyes. Same polished soft 3D illustrated style.
Change: Make the WORKING/FOCUSED pose more readable with creative wing acting. Raise one teal-and-gold wing RIGHT BESIDE ITS FACE, feather tips pointing diagonally forward with a purposeful directing/helping gesture. Feather tips form a natural peacock wing, no human hands or fingers. The wing is at eye or beak height, not at the bottom of the portrait. The peacock leans its very large head slightly forward, eyes focused and attentive, brows gently drawn down in concentration, serious but friendly, beak closed. It is actively checking/helping with a task. Distinguish from confusion: level head, focused brow, confident directional wing.
Composition: A CLOSE-UP compact square chest-up portrait on real transparent background. Large face, eyes and beak occupy at least 55% of image width; compact crest with EXACTLY FIVE blue plumes fits above without clipping. Only short neck/upper chest and a SMALL fragment of emerald tail behind other shoulder. Main visual is FACE PLUS DIRECTIONAL WING, recognizable when reduced to 48 pixels. Entire head/crest/wing within frame with 6% margin. No feet or full body.
Avoid: text, emoji, symbol, tools, accessories, screens, scenery, duplicate characters, changed identity, small head, long neck, dominant tail, bottom-only wing gesture, human anatomy, background.
```
