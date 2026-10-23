# BOOX native preview visibility results

The user completed all 63 physical pen samples on the connected NoteAir6C:
**46 requests were visible; 17 were invisible on the white canvas.**

These are RGB requests, not 46 verified distinct output colors. Visibility does
not mean that the native preview matches the requested RGB. The earlier
blue-gray preview, for example, was reported to look black.

| Original ink request | Live visibility |
| --- | --- |
| Pale blue #C7D8E8 | Invisible |
| Light blue #9CBBD6 | Invisible |
| Original blue #6F9EC5 | Visible |
| Deep blue #315F88 | Visible |
| Blue-gray #566B78 | Visible |
| Black #000000 | Visible |

Invisible requests:

`#8080FF #80B0FF #80E0FF #80FF80 #80FFFF #9CBBD6 #B080FF #B0B0FF #B0E0FF #C7D8E8 #E080FF #E0B0FF #E0E0FF #FF8080 #FF80FF #FFFF80 #FFFFFF`

The app hides those 17 requests from the optional preview matching palette.
All six finished inks remain available. For normal drawing, the five finished
blue shades automatically share the original blue preview, which was previously
reported to look blue while drawing. Black uses black. Finished pixels retain
the exact selected RGB after pen-up. Saved user favorites can override these
defaults. No further manual matching is required to use this prototype.

This is an approximate preview configuration for the prototype palette, not
a measured full-gamut color conversion. Neither the number of distinct native
preview colors nor their exact physical colors has been established.

- [All 63 observations, with timestamps and firmware](artifacts/preview-visibility-results.csv)
- [Original saved records](artifacts/preview-visibility-results.xml)
