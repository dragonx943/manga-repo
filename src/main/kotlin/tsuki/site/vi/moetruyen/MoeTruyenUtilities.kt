package tsuki.site.vi.moetruyen

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import tsuki.model.MangaPage
import tsuki.model.MangaSource
import tsuki.network.CommonHeaders
import tsuki.network.WebClient
import tsuki.util.generateUid
import tsuki.util.json.getStringOrNull
import tsuki.util.parseJson
import tsuki.util.urlBuilder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val cache by lazy {
	File(System.getProperty("java.io.tmpdir"), "moe_truyen_cache").apply { mkdirs() }
}

/**
 * Some dirty hacks for MoeTruyenUnofficial parser
 * MangaPage & SSIV not accept .js from API, since it's not a limit.
 * Before push to MangaPage, that .js file must be decoded and cached (if any).
 * Nà ná nà na, shout out to Koitharu with some fixes for better performance.
**/

internal suspend fun resolve(
	webClient: WebClient, apiDomain: String, apiSuffix: String,
	domain: String, source: MangaSource, chapterId: Int, pageCount: Int,
): List<MangaPage> {
	val pages = mutableListOf<MangaPage>()
	val postHeaders = Headers.Builder()
		.add("Accept", "application/json")
		.add("Content-Type", "application/json")
		.add(CommonHeaders.ORIGIN, "https://$domain")
		.build()

	for (start in 0 until pageCount step 5) {
		val end = minOf(start + 5, pageCount)
		val json = JSONObject().apply { put("pageIndexes", JSONArray((start until end).toList())) }
		val url = urlBuilder(apiDomain).addEncodedPathSegments("$apiSuffix/chapters/$chapterId/page-access").build()
		val response = runCatching {
			webClient.httpPost(url, json, postHeaders).parseJson()
		}.getOrNull() ?: break
		val arr = response.optJSONObject("data")?.optJSONArray("pages") ?: continue

		for (i in 0 until arr.length()) {
			val entryObj = arr.getJSONObject(i)
			val download = entryObj.getStringOrNull("downloadUrl").orEmpty()
			val key = entryObj.getStringOrNull("storageKey").orEmpty()
			val i = entryObj.optInt("pageIndex", start + i)
			val grant = parseImgxGrant(entryObj.optJSONObject("grant"))

			if (download.isNotBlank()) {
				if (grant != null) {
					val uid = generateUid(source, "$chapterId-$i-$key")
					val local = File(cache, "$uid.webp")

					if (!local.exists() || local.length() == 0L) {
						runCatching {
							val raw = webClient.httpGet(download).body.bytes()
							if (raw.size >= 4 && raw[0] == 0x49.toByte() && raw[1] == 0x4D.toByte() &&
								raw[2] == 0x47.toByte() && raw[3] == 0x58.toByte()
							) {
								val decrypted = ImageDecryptor.decrypt(raw, grant, key)
								local.outputStream().use { it.write(decrypted.data, decrypted.offset, decrypted.size) }
							} else local.writeBytes(raw)
						}
					}

					val finalUrl = if (local.exists() && local.length() > 0L) "file://" + local.absolutePath else download
					pages.add(
						MangaPage(
							id = generateUid(source, finalUrl),
							url = finalUrl,
							preview = null,
							source = source,
						)
					)
				} else {
					pages.add(
						MangaPage(
							id = generateUid(source, download),
							url = download,
							preview = null,
							source = source,
						)
					)
				}
			}
		}
	}
	return pages
}

/**
 * Decryptor for MoeTruyenUnofficial parser
 * Refer from keiyoushi/extensions-source (by FiorenMas).
 * Nà ná nà na, shout out to Gemini with some patches for better performance.
**/

internal data class ImgxGrant(
	val version: Int?, val algorithm: String?, val decodeKey: String?, val wrappedDecodeKey: String?,
	val wrappedContentKey: String?, val imageId: String?, val issuedAt: Long?, val expiresAt: Long?,
	val nonce: String?, val keyNonce: String?, val signature: String?,
)

internal class DecryptedImage(val data: ByteArray, val offset: Int, val size: Int)

internal fun parseImgxGrant(obj: JSONObject?): ImgxGrant? = obj?.run {
	ImgxGrant(
		version = optInt("version", 1).takeIf { has("version") },
		algorithm = getStringOrNull("algorithm"),
		decodeKey = getStringOrNull("decodeKey"),
		wrappedDecodeKey = getStringOrNull("wrappedDecodeKey"),
		wrappedContentKey = getStringOrNull("wrappedContentKey"),
		imageId = getStringOrNull("imageId"),
		issuedAt = optLong("issuedAt", 0L).takeIf { has("issuedAt") },
		expiresAt = optLong("expiresAt", 0L).takeIf { has("expiresAt") },
		nonce = getStringOrNull("nonce"),
		keyNonce = getStringOrNull("keyNonce"),
		signature = getStringOrNull("signature"),
	)
}

internal object ImageDecryptor {
	private const val GOLDEN_RATIO = 0x9E3779B9.toInt()

	fun decrypt(imgxData: ByteArray, grant: ImgxGrant, storageKey: String): DecryptedImage {
		require(imgxData.size > 13) { "IMGX payload empty" }
		require(imgxData[0] == 0x49.toByte() && imgxData[1] == 0x4D.toByte() && imgxData[2] == 0x47.toByte() && imgxData[3] == 0x58.toByte()) { "IMGX magic invalid" }

		return when (val v = imgxData[4].toInt()) {
			2 -> decryptV2(imgxData, grant, storageKey)
			3 -> decryptV3(imgxData, grant, storageKey)
			else -> throw IllegalArgumentException("Unsupported IMGX version: $v")
		}
	}

	private fun decryptV2(imgxData: ByteArray, grant: ImgxGrant, storageKey: String): DecryptedImage {
		val key = unwrapKey(grant, storageKey, null)
		unshuffle(imgxData, 13, key)
		xorDecrypt(imgxData, 13, key)
		return DecryptedImage(imgxData, 13, imgxData.size - 13)
	}

	private fun decryptV3(imgxData: ByteArray, grant: ImgxGrant, storageKey: String): DecryptedImage {
		if (grant.wrappedContentKey != null) return decryptV3AesGcm(imgxData, grant, storageKey)

		val headerSize = imgxData.readUShortBE(6)
		val headerObj = JSONObject(String(imgxData.copyOfRange(8, 8 + headerSize), Charsets.UTF_8))
		val grantSalt = headerObj.getStringOrNull("grantSalt")
		val blockSize = headerObj.optInt("blockSize", 0).takeIf { headerObj.has("blockSize") }

		val offset = 8 + headerSize
		val key = unwrapKey(grant, storageKey, grantSalt)

		if (blockSize != null && blockSize > 0) blockCipherDecrypt(imgxData, offset, key, blockSize)
		unshuffle(imgxData, offset, key)
		xorDecrypt(imgxData, offset, key)

		return DecryptedImage(imgxData, offset, imgxData.size - offset)
	}

	private fun decryptV3AesGcm(imgxData: ByteArray, grant: ImgxGrant, storageKey: String): DecryptedImage {
		require(imgxData.size > 41) { "IMGX v3 payload empty" }
		val width = imgxData.readIntBE(5)
		val height = imgxData.readIntBE(9)
		require(width > 0 && height > 0) { "IMGX dimensions invalid" }

		val key = unwrapContentKey(grant, storageKey)
		val iv = imgxData.copyOfRange(13, 25)
		val imageId = grant.imageId?.trim().orEmpty()
		require(imageId.isNotEmpty()) { "IMGX v3 image id missing" }
		val aad = "IMGX-v3.$imageId.${storageKey.trimStart('/')}.$width.$height".toByteArray(Charsets.UTF_8)

		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
			updateAAD(aad)
		}
		val decrypted = cipher.doFinal(imgxData, 25, imgxData.size - 25)
		return DecryptedImage(decrypted, 0, decrypted.size)
	}

	private fun blockCipherDecrypt(data: ByteArray, offset: Int, key: ByteArray, blockSize: Int) {
		val len = data.size - offset
		val numBlocks = (len + blockSize - 1) / blockSize
		var seed = seedFromKey(key)
		val seeds = IntArray(numBlocks) { seed = xorshift32(seed); seed }

		for (b in numBlocks - 1 downTo 0) {
			val start = offset + b * blockSize
			val blockLen = minOf(start + blockSize, data.size) - start
			val blockSeed = seeds[b]
			val blockKey = (blockSeed and 0xFF).toByte()
			val rotate = (blockSeed ushr 8) % blockLen
			val temp = ByteArray(blockLen) { (data[start + it].toInt() xor blockKey.toInt()).toByte() }
			for (i in 0 until blockLen) {
				data[start + i] = temp[((i - rotate) % blockLen + blockLen) % blockLen]
			}
		}
	}

	private fun unwrapKey(grant: ImgxGrant, storageKey: String, grantSalt: String?): ByteArray {
		val wrappedKey = grant.wrappedDecodeKey
		if (wrappedKey != null) {
			val wrapped = base64UrlDecode(wrappedKey)
			require(wrapped.size == 32) { "IMGX wrapped grant invalid" }
			val unwrapKey = deriveKeyFromString(buildGrantString(grant, storageKey, grantSalt))
			for (i in wrapped.indices) wrapped[i] = (wrapped[i].toInt() xor unwrapKey[i].toInt()).toByte()
			return wrapped
		}
		val decodeKey = grant.decodeKey ?: throw IllegalArgumentException("IMGX decodeKey missing")
		return base64UrlDecode(decodeKey)
	}

	private fun unwrapContentKey(grant: ImgxGrant, storageKey: String): ByteArray {
		val wrappedContentKey = grant.wrappedContentKey ?: throw IllegalArgumentException("IMGX wrappedContentKey missing")
		val wrapped = base64UrlDecode(wrappedContentKey)
		require(wrapped.size == 32) { "IMGX wrapped content grant invalid" }
		val unwrapKey = deriveKeyFromString(buildGrantString(grant, storageKey, null))
		for (i in wrapped.indices) wrapped[i] = (wrapped[i].toInt() xor unwrapKey[i].toInt()).toByte()
		return wrapped
	}

	private fun buildGrantString(g: ImgxGrant, sk: String, salt: String?): String {
		val parts = mutableListOf(
			"IMGX-GRANT-WRAP-v1", g.version?.toString().orEmpty(), g.algorithm.orEmpty(), g.imageId.orEmpty(),
			g.issuedAt?.toString().orEmpty(), g.expiresAt?.toString().orEmpty(), g.nonce.orEmpty(),
			g.keyNonce.orEmpty(), g.signature.orEmpty(), sk.trimStart('/'),
		)
		if (!salt.isNullOrEmpty()) parts.add(salt)
		return parts.joinToString(".")
	}

	private fun deriveKeyFromString(input: String): ByteArray {
		val key = ByteArray(32)
		var hash = fnv1a(input.toByteArray(Charsets.UTF_8))
		for (i in 0 until 32) {
			if (i % 4 == 0) hash = xorshift32(hash + i + GOLDEN_RATIO)
			key[i] = (hash ushr ((i % 4) * 8) and 0xFF).toByte()
		}
		return key
	}

	private fun fnv1a(data: ByteArray): Int {
		var hash = 2166136261.toInt()
		for (b in data) {
			hash = (hash xor (b.toInt() and 0xFF)) * 16777619
		}
		return if (hash == 0) GOLDEN_RATIO else hash
	}

	private fun xorshift32(input: Int): Int {
		var t = input
		t = t xor (t shl 13)
		t = t xor (t ushr 17)
		t = t xor (t shl 5)
		return t
	}

	private fun unshuffle(data: ByteArray, offset: Int, key: ByteArray) {
		val len = data.size - offset
		if (len <= 1) return
		val perm = IntArray(len) { it }
		var seed = seedFromKey(key)
		for (i in len - 1 downTo 1) {
			seed = xorshift32(seed)
			val j = ((seed.toLong() and 0xFFFFFFFFL) % (i + 1)).toInt()
			val tmp = perm[i]
			perm[i] = perm[j]
			perm[j] = tmp
		}
		val temp = data.copyOfRange(offset, data.size)
		for (i in 0 until len) data[offset + perm[i]] = temp[i]
	}

	private fun xorDecrypt(data: ByteArray, offset: Int, key: ByteArray) {
		var seed = seedFromKey(key)
		val len = data.size - offset
		for (i in 0 until len) {
			seed = xorshift32(seed)
			data[offset + i] = (data[offset + i].toInt() xor (seed and 0xFF)).toByte()
		}
	}

	private fun seedFromKey(key: ByteArray): Int {
		var seed = 0
		for (i in key.indices) seed = seed xor ((key[i].toInt() and 0xFF) shl ((i % 4) * 8))
		return if (seed == 0) GOLDEN_RATIO else seed
	}

	private fun base64UrlDecode(input: String): ByteArray {
		var base64 = input.replace('-', '+').replace('_', '/')
		val padding = (4 - (base64.length % 4)) % 4
		if (padding > 0) base64 += "=".repeat(padding)
		return java.util.Base64.getDecoder().decode(base64)
	}

	private fun ByteArray.readUShortBE(offset: Int): Int =
		((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

	private fun ByteArray.readIntBE(offset: Int): Int =
		((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
			((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)
}
