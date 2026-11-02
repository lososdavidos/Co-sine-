package app.sine.desktop.ui

import androidx.compose.runtime.mutableStateListOf

/** Where you can be. The first ones are the sidebar's; the rest open from them. */
sealed interface Dest {
    data object Home : Dest
    data class Search(val query: String = "") : Dest
    data object Artists : Dest
    data object Albums : Dest
    data object Playlists : Dest
    data object Downloads : Dest
    data object Jam : Dest
    data object Devices : Dest
    data object Settings : Dest
    data class Artist(val id: String, val name: String) : Dest
    data class Album(val id: String, val name: String) : Dest
    data class Playlist(val id: String, val name: String) : Dest

    companion object {
        val Liked = Playlist(app.sine.core.model.Playlist.LIKED, "Liked songs")
    }
}

/** The pages you went through, to go back: a sidebar place starts afresh. */
class Nav {
    val stack = mutableStateListOf<Dest>(Dest.Home)
    val current: Dest get() = stack.last()

    fun go(dest: Dest) {
        val top = dest is Dest.Home || dest is Dest.Search || dest is Dest.Artists || dest is Dest.Albums ||
            dest is Dest.Playlists || dest is Dest.Downloads || dest is Dest.Jam || dest is Dest.Devices ||
            dest is Dest.Settings || dest == Dest.Liked
        if (current == dest) return
        if (top) stack.clear()
        stack.add(dest)
    }

    /** Search keeps one page, its query changing as you type. */
    fun search(query: String) {
        if (current is Dest.Search) stack[stack.lastIndex] = Dest.Search(query) else go(Dest.Search(query))
    }

    val canGoBack: Boolean get() = stack.size > 1

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** The sidebar place a page belongs under, to light it. */
fun Dest.root(): Dest? = when (this) {
    is Dest.Artist -> Dest.Artists
    is Dest.Album -> Dest.Albums
    is Dest.Playlist -> if (this == Dest.Liked) this else Dest.Playlists
    else -> this
}
