package app.sine.core.model

/** One row of an artist page (§6.9): a release, or a single shown as the song it is. */
sealed interface ArtistEntry {
    val album: Album

    data class Release(override val album: Album) : ArtistEntry
    data class Single(override val album: Album, val track: Track) : ArtistEntry
}

object ArtistPage {
    /**
     * The Store files a track with no album as a release of one song named
     * after it (§2.2). On the artist page that is a single, so it shows as
     * the song: one row to tap, not a folder holding one track. Releases come
     * as given, in their order; [tracksOf] answers for the ones it knows,
     * and a release it doesn't stays a release.
     */
    fun entries(albums: List<Album>, tracksOf: (Album) -> List<Track>?): List<ArtistEntry> =
        albums.map { album ->
            val only = if (album.trackCount <= 1) tracksOf(album)?.singleOrNull() else null
            if (only != null && only.title.trim().equals(album.name.trim(), ignoreCase = true)) {
                ArtistEntry.Single(album, only)
            } else {
                ArtistEntry.Release(album)
            }
        }
}
