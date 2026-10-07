// OPT-IN network probe: uploads only the generated fixture, never user content.
// Compile with KeyboardGifUpload.kt using kotlinc -include-runtime; run from repository root.
// Not part of CI/unit tests because public hosting availability is not deterministic.
package app.party.music
fun main() {
    val proxy = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
    if (!proxy.isNullOrBlank()) {
        val uri=java.net.URI(proxy)
        System.setProperty("https.proxyHost",uri.host)
        System.setProperty("https.proxyPort",(if(uri.port>0) uri.port else 80).toString())
    }
    val b=java.io.File("tests/fixtures/keyboard-upload.gif").readBytes()
    val f=KeyboardGifUpload.format(b)
    try { println("PRIMARY: "+KeyboardGifUpload.primary(b,f)) } catch(e: Exception) { println("PRIMARY: "+KeyboardGifUpload.reason(e)) }
    try { println("BACKUP byte-verified: "+KeyboardGifUpload.backup(b,f)) } catch(e: Exception) { println("BACKUP: "+KeyboardGifUpload.reason(e));kotlin.system.exitProcess(1) }
}
