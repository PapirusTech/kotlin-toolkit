/*
 * Module: r2-streamer-kotlin
 * Developers: Quentin Gliosca
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.streamer.parser.epub

import java.util.LinkedList
import org.readium.r2.shared.extensions.toMap
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.Presentation

internal fun Epub.computeMetadata() : Metadata {
    val (media, mediaRemainder) = parseMediaProperties(packageDocument.metadata.globalMetas)
    val (presentation, presRemainder) = parseRenditionProperties(mediaRemainder)
    val otherMetadata: MutableMap<String, Any> = mutableMapOf()
    otherMetadata["presentation"] = presentation.toJSON().toMap()
    presRemainder.forEach { otherMetadata[it.property] = it.toMap() }

    val (title, sortAs) = getMaintitle()
    val metadata = packageDocument.metadata.general
    val contributorsByRole = ContributorsByRole()
    addContributors(metadata.narrators.map { mapContributor(it, "nrt") }, contributorsByRole)
    addContributors(metadata.publishers.map { mapContributor(it, "pbl") }, contributorsByRole)
    addContributors(metadata.creators.map { mapContributor(it, "aut") }, contributorsByRole)
    addContributors(metadata.contributors.map { mapContributor(it) }, contributorsByRole)

    return Metadata(
            identifier = metadata.uniqueIdentifier,
            modified = metadata.modified,
            published = metadata.date,
            languages = metadata.languages,
            localizedTitle = title ?: LocalizedString(),
            sortAs = sortAs,
            localizedSubtitle = getSubtitle(),
            duration = media.duration,
            subjects = mapSubjects(metadata.subjects),
            description = metadata.description,
            readingProgression = packageDocument.spine.direction,
            otherMetadata = otherMetadata,

            authors = contributorsByRole.authors,
            translators = contributorsByRole.translators,
            editors = contributorsByRole.editors,
            artists = contributorsByRole.artists,
            illustrators = contributorsByRole.illustrators,
            letterers = contributorsByRole.letterers,
            pencilers = contributorsByRole.pencilers,
            colorists = contributorsByRole.colorists,
            inkers = contributorsByRole.inkers,
            narrators = contributorsByRole.narrators,
            publishers = contributorsByRole.publishers,
            contributors = contributorsByRole.others
    )
}

private fun mapLocalizedString(lstring: LocalizedString, languages: List<String>) : LocalizedString {
    return lstring.mapLanguages {
        if (it.key.isNullOrEmpty()) {
            if (languages.isEmpty())
                null
            else languages.first()
        } else it.key
    }
}

private fun Epub.getMaintitle() : Pair<LocalizedString?, String?> {
    val metadata = packageDocument.metadata.general
    val titles = metadata.titles
    val main =  titles.firstOrNull { it.type == "main" } ?: titles.firstOrNull()
    val lstring = main?.value?.let { mapLocalizedString( it , metadata.languages) }
    val sortAs = main?.fileAs
    return Pair(lstring, sortAs)
}

private fun Epub.getSubtitle() : LocalizedString? {
    val metadata = packageDocument.metadata.general
    val titles = metadata.titles
    val sub =  titles.filter { it.type == "subtitle" }.sortedBy(Title::displaySeq).firstOrNull()
    return sub?.value?.let { mapLocalizedString( it , metadata.languages) }
}

private fun Epub.mapSubjects(subjects: List<Subject>) : List<Subject> {
    val languages = packageDocument.metadata.general.languages
    val newSubjects = subjects.map {
        val localizedName = mapLocalizedString(it.localizedName, languages)
        it.copy(localizedName=localizedName)
    }
    val hasToSplit = subjects.size == 1 && subjects.first().run {
                localizedName.translations.size == 1 && code == null && scheme == null && sortAs == null}
    return if (hasToSplit) splitSubject(newSubjects.first()) else newSubjects
}

private fun splitSubject(subject: Subject) : List<Subject> {
    val lang = subject.localizedName.translations.keys.first()
    val names = subject.localizedName.translations.values.first().string.split(",", ";")
            .map(kotlin.String::trim).filter(kotlin.String::isNotEmpty)
    return names.map {
        val newName = LocalizedString.fromStrings(mapOf(lang to it))
        Subject(localizedName=newName)
    }
}

private fun Epub.mapContributor(contributor: Contributor, defaultRole: String? = null) : Contributor {
    val metadata = packageDocument.metadata.general
    val lstring = mapLocalizedString(contributor.localizedName, metadata.languages)
    val roles = contributor.roles.toMutableSet()
    if (roles.isEmpty() && defaultRole != null) roles.add(defaultRole)

    return Contributor(localizedName=lstring, sortAs=contributor.sortAs, roles=roles
    )
}

private data class ContributorsByRole(
        val authors: MutableList<Contributor> = mutableListOf(),
        val translators: MutableList<Contributor> = mutableListOf(),
        val editors: MutableList<Contributor> = mutableListOf(),
        val artists: MutableList<Contributor> = mutableListOf(),
        val illustrators: MutableList<Contributor> = mutableListOf(),
        val letterers: MutableList<Contributor> = mutableListOf(),
        val pencilers: MutableList<Contributor> = mutableListOf(),
        val colorists: MutableList<Contributor> = mutableListOf(),
        val inkers: MutableList<Contributor> = mutableListOf(),
        val narrators: MutableList<Contributor> = mutableListOf(),
        val publishers: MutableList<Contributor> = mutableListOf(),
        val imprints: MutableList<Contributor> = mutableListOf(),
        val others: MutableList<Contributor> = mutableListOf()
)

private fun addContributors(contributors: List<Contributor>, byRole: ContributorsByRole) {
    for (contributor in contributors) {
        if (contributor.roles.isEmpty())
            byRole.others.add(contributor)
        for (role in contributor.roles) {
            when (role) {
                "aut" -> byRole.authors.add(contributor)
                "trl" -> byRole.translators.add(contributor)
                "art" -> byRole.artists.add(contributor)
                "edt" -> byRole.editors.add(contributor)
                "ill" -> byRole.illustrators.add(contributor)
                "clr" -> byRole.colorists.add(contributor)
                "nrt" -> byRole.narrators.add(contributor)
                "pbl" -> byRole.publishers.add(contributor)
                else -> byRole.others.add(contributor)
            }
        }
    }
}

private fun parseMediaProperties(metas: List<MetaItem>): Pair<MediaMetadata, List<MetaItem>> {
    val remainder: MutableList<MetaItem> = mutableListOf()
    var activeClass: String? = null
    var playbackActiveClass: String? = null
    var duration: Double? = null
    val narrators: MutableList<Contributor> = LinkedList()

    for (meta in metas) {
        when (meta.property) {
            PACKAGE_RESERVED_PREFIXES["media"] + "active-class" -> activeClass = meta.value
            PACKAGE_RESERVED_PREFIXES["media"] + "playback-active-class" -> playbackActiveClass = meta.value
            PACKAGE_RESERVED_PREFIXES["media"] + "duration" -> duration = ClockValueParser.parse(meta.value)
            else -> remainder.add(meta)
        }
    }
    val metadata = MediaMetadata(duration, activeClass, playbackActiveClass, narrators)
    return Pair(metadata, remainder)
}

private fun parseRenditionProperties(metaItems: List<MetaItem>) : Pair<Presentation, List<MetaItem>> {
    val remainder: MutableList<MetaItem> = mutableListOf()
    var flowProp: String? = null
    var spreadProp: String? = null
    var orientationProp: String? = null
    var layoutProp: String? = null

    for (meta in metaItems) {
        when (meta.property) {
            PACKAGE_RESERVED_PREFIXES["rendition"] + "flow" -> flowProp = meta.value
            PACKAGE_RESERVED_PREFIXES["rendition"] + "layout" -> layoutProp = meta.value
            PACKAGE_RESERVED_PREFIXES["rendition"] + "orientation" -> orientationProp = meta.value
            PACKAGE_RESERVED_PREFIXES["rendition"] + "spread" -> spreadProp = meta.value
            else -> remainder.add(meta)
        }
    }

    val (overflow, continuous) = when(flowProp) {
        "paginated" -> Pair(Presentation.Overflow.PAGINATED, false)
        "scrolled-continuous" -> Pair(Presentation.Overflow.SCROLLED, true)
        "scrolled-doc" -> Pair(Presentation.Overflow.SCROLLED, false)
        else -> Pair(Presentation.Overflow.AUTO, false)
    }

    val layout = when(layoutProp) {
        "pre-paginated" -> EpubLayout.FIXED
        else -> EpubLayout.REFLOWABLE
    }

    val orientation = when(orientationProp) {
        "landscape" -> Presentation.Orientation.LANDSCAPE
        "portrait" -> Presentation.Orientation.PORTRAIT
        else -> Presentation.Orientation.AUTO
    }

    val spread = when(spreadProp) {
        "none" -> Presentation.Spread.NONE
        "landscape" -> Presentation.Spread.LANDSCAPE
        "both", "portrait" -> Presentation.Spread.BOTH
        else -> Presentation.Spread.AUTO
    }
    val presentation = Presentation(overflow = overflow, continuous = continuous,
            layout = layout, orientation = orientation, spread = spread)
    return Pair(presentation, remainder)
}

internal fun MetaItem.toMap() : Any {
    return if (children.isEmpty())
        value
    else {
        val mappedChildren = children.associate { Pair(it.property, it.toMap()) }
        mappedChildren + Pair("@value", value)
    }
}

internal data class MediaMetadata(
        val duration: Double?,
        val activeClass: String?,
        val playbackActiveClass: String?,
        val narrators: List<Contributor>
)
