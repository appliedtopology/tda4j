package org.appliedtopology.tda4j
package unicode

def applyTranslation(trMap: Map[Char, String]): (String => String) = s => s.flatMap(trMap.orElse(_.toString))

def applyCharTranslation(trMap: Map[Char, Char]): (String => String) = s => s.map(trMap.orElse(c => c))

val superScriptMap = Map(
  '0' -> '⁰',
  '1' -> '¹',
  '2' -> '²',
  '3' -> '³',
  '4' -> '⁴',
  '5' -> '⁵',
  '6' -> '⁶',
  '7' -> '⁷',
  '8' -> '⁸',
  '9' -> '⁹',
  '+' -> '⁺',
  '-' -> '⁻',
  '=' -> '⁼',
  '(' -> '⁽',
  ')' -> '⁾',
  'i' -> 'ⁱ',
  'n' -> 'ⁿ',
  'h' -> 'ʰ'
)

val subScriptMap = Map(
  'i' -> 'ᵢ',
  'r' -> 'ᵣ',
  'u' -> 'ᵤ',
  'v' -> 'ᵥ',
  '0' -> '₀',
  '1' -> '₁',
  '2' -> '₂',
  '3' -> '₃',
  '4' -> '₄',
  '5' -> '₅',
  '6' -> '₆',
  '7' -> '₇',
  '8' -> '₈',
  '9' -> '₉',
  '+' -> '₊',
  '-' -> '₋',
  '=' -> '₌',
  '(' -> '₍',
  ')' -> '₎',
  'a' -> 'ₐ',
  'e' -> 'ₔ',
  'o' -> 'ₒ',
  'x' -> 'ₓ',
  'h' -> 'ₕ',
  'k' -> 'ₖ',
  'l' -> 'ₗ',
  'm' -> 'ₘ',
  'n' -> 'ₙ',
  'p' -> 'ₚ',
  's' -> 'ₛ',
  't' -> 'ₜ',
  'j' -> 'ⱼ'
)

def unicodeSuperScript = applyCharTranslation(superScriptMap)
def unicodeSubScript = applyCharTranslation(subScriptMap)
