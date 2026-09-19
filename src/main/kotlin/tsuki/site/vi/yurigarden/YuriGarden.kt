package tsuki.site.vi.yurigarden

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource

@MangaSourceParser("YURIGARDEN", "Yuri Garden", "vi")
internal class YuriGarden(context: MangaLoaderContext) :
	YuriGardenParser(context, MangaParserSource.YURIGARDEN, "yurigarden.com", false)
