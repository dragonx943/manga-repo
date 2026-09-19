package tsuki.site.vi.yurigarden

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("YURIGARDEN_R18", "Yuri Garden (18+)", "vi", type = ContentType.HENTAI)
internal class YuriGardenR18(context: MangaLoaderContext) :
	YuriGardenParser(context, MangaParserSource.YURIGARDEN_R18, "yurigarden.com", true)
