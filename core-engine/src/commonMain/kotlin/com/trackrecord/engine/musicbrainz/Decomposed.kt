package com.trackrecord.engine.musicbrainz

/** [text] in Unicode canonical decomposition (NFD), so diacritics become marks of their own. */
internal expect fun decomposed(text: String): String
