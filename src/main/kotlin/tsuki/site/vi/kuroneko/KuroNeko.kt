package tsuki.site.vi.kuroneko

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("KURONEKO", "Kuro Neko / vi-Hentai", "vi", ContentType.HENTAI)
internal class KuroNeko(context: MangaLoaderContext) :
    KuroNekoParser(context, MangaParserSource.KURONEKO) {
    override val configKeyDomain = ConfigKey.Domain("vi-hentai.pro", "vi-hentai.moe")
}