package io.github.golangsupport.ide.injection

import com.intellij.psi.PsiElement
import org.intellij.lang.regexp.RegExpCapabilitiesProvider
import org.intellij.lang.regexp.RegExpCapability
import org.intellij.lang.regexp.RegExpLanguageHost
import org.intellij.lang.regexp.psi.RegExpBoundary
import org.intellij.lang.regexp.psi.RegExpChar
import org.intellij.lang.regexp.psi.RegExpElement
import org.intellij.lang.regexp.psi.RegExpGroup
import org.intellij.lang.regexp.psi.RegExpNamedGroupRef
import java.util.EnumSet

/**
 * The RE2 dialect of Go's `regexp` for regular expressions injected into Go strings (the platform asks the host registered for the
 * injection host's class). No lookbehind, no possessive quantifiers, no `\k<name>`, no duplicate group names; named groups are
 * `(?P<n>...)` and `(?<n>...)` (Go 1.22); flags are `i m s U`. Lookahead, atomic groups and backreferences have no host hook in the
 * platform's annotator: [GoRegExpAnnotator] reports them.
 */
class GoRegExpLanguageHost : RegExpLanguageHost {
    override fun supportsPerl5EmbeddedComments(): Boolean = false
    override fun supportsPossessiveQuantifiers(): Boolean = false
    override fun supportsPossessiveQuantifiers(element: RegExpElement): Boolean = false
    override fun isDuplicateGroupNamesAllowed(group: RegExpGroup): Boolean = false
    override fun supportsPythonConditionalRefs(): Boolean = false
    override fun supportsNamedGroupSyntax(group: RegExpGroup): Boolean = group.type in NAMED
    override fun supportsNamedGroupRefSyntax(ref: RegExpNamedGroupRef): Boolean = false
    override fun getSupportedNamedGroupTypes(context: RegExpElement): EnumSet<RegExpGroup.Type> = EnumSet.copyOf(NAMED)
    override fun supportsExtendedHexCharacter(regExpChar: RegExpChar): Boolean = true
    override fun supportsLookbehind(lookbehind: RegExpGroup): RegExpLanguageHost.Lookbehind = RegExpLanguageHost.Lookbehind.NOT_SUPPORTED
    override fun supportsInlineOptionFlag(flag: Char, context: PsiElement): Boolean = flag in "imsU"

    /** `\A` and `\z` exist; `\Z`, `\G`, `\K` and grapheme boundaries do not. */
    override fun supportsBoundary(boundary: RegExpBoundary): Boolean = boundary.type in BOUNDARIES

    // `\p{Greek}`, `\pL`, `\p{^Lu}`: nothing is flagged (the script list follows the Unicode version of the toolchain).
    override fun isValidCategory(category: String): Boolean = true
    override fun isValidPropertyName(name: String): Boolean = true

    // The platform's RegExp completion offers exactly these rows after `\` and `\p{` (empty tables gave 0 rows, seen live).
    override fun getAllKnownProperties(): Array<Array<String>> = GoRegExpSyntax.PROPERTIES
    override fun getPropertyDescription(name: String?): String? = GoRegExpSyntax.propertyDescription(name)
    override fun getKnownCharacterClasses(): Array<Array<String>> = GoRegExpSyntax.CHARACTER_CLASSES

    private companion object {
        val NAMED: Set<RegExpGroup.Type> = EnumSet.of(RegExpGroup.Type.NAMED_GROUP, RegExpGroup.Type.PYTHON_NAMED_GROUP)
        val BOUNDARIES: Set<RegExpBoundary.Type> = EnumSet.of(
            RegExpBoundary.Type.LINE_START, RegExpBoundary.Type.LINE_END, RegExpBoundary.Type.WORD, RegExpBoundary.Type.NON_WORD,
            RegExpBoundary.Type.BEGIN, RegExpBoundary.Type.END,
        )
    }
}

/** What the platform's lexer must accept in RE2: POSIX classes `[[:alpha:]]`, `\pL`, `\x{10FFFF}`. Registered for the Go language (the injection host's). */
class GoRegExpCapabilities : RegExpCapabilitiesProvider {
    override fun setup(host: PsiElement, default: Set<RegExpCapability>): Set<RegExpCapability> =
        default + RegExpCapability.POSIX_BRACKET_EXPRESSIONS + RegExpCapability.UNICODE_CATEGORY_SHORTHAND + RegExpCapability.EXTENDED_UNICODE_CHARACTER
}
