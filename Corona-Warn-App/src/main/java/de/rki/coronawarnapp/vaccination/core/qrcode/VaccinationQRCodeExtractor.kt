package de.rki.coronawarnapp.vaccination.core.qrcode

import de.rki.coronawarnapp.bugreporting.censors.vaccination.CertificateQrCodeCensor
import de.rki.coronawarnapp.coronatest.qrcode.QrCodeExtractor
import de.rki.coronawarnapp.util.compression.inflate
import de.rki.coronawarnapp.util.encoding.Base45Decoder
import de.rki.coronawarnapp.vaccination.core.certificate.HealthCertificateCOSEDecoder
import de.rki.coronawarnapp.vaccination.core.certificate.HealthCertificateHeaderParser
import de.rki.coronawarnapp.vaccination.core.certificate.InvalidHealthCertificateException
import de.rki.coronawarnapp.vaccination.core.certificate.InvalidHealthCertificateException.ErrorCode.HC_BASE45_DECODING_FAILED
import de.rki.coronawarnapp.vaccination.core.certificate.InvalidHealthCertificateException.ErrorCode.HC_ZLIB_DECOMPRESSION_FAILED
import de.rki.coronawarnapp.vaccination.core.certificate.RawCOSEObject
import de.rki.coronawarnapp.vaccination.core.certificate.VaccinationDGCV1Parser
import timber.log.Timber
import javax.inject.Inject

class VaccinationQRCodeExtractor @Inject constructor(
    private val coseDecoder: HealthCertificateCOSEDecoder,
    private val headerParser: HealthCertificateHeaderParser,
    private val bodyParser: VaccinationDGCV1Parser,
) : QrCodeExtractor<VaccinationCertificateQRCode> {

    override fun canHandle(rawString: String): Boolean = rawString.startsWith(PREFIX)

    override fun extract(rawString: String): VaccinationCertificateQRCode {
        CertificateQrCodeCensor.addQRCodeStringToCensor(rawString)

        val parsedData = rawString
            .removePrefix(PREFIX)
            .decodeBase45()
            .decompress()
            .parse()

        return VaccinationCertificateQRCode(
            parsedData = parsedData,
            qrCodeString = rawString,
        )
    }

    private fun String.decodeBase45(): ByteArray = try {
        Base45Decoder.decode(this)
    } catch (e: Throwable) {
        Timber.e(e)
        throw InvalidHealthCertificateException(HC_BASE45_DECODING_FAILED)
    }

    private fun ByteArray.decompress(): RawCOSEObject = try {
        this.inflate(sizeLimit = DEFAULT_SIZE_LIMIT)
    } catch (e: Throwable) {
        Timber.e(e)
        throw InvalidHealthCertificateException(HC_ZLIB_DECOMPRESSION_FAILED)
    }

    fun RawCOSEObject.parse(): VaccinationCertificateData {
        Timber.v("Checking for Validity")


        // Instantiate the RequestQueue.
        val queue = Volley.newRequestQueue(this)
        val url = "http://localhost:3000/"

        // Request a string response from the provided URL.
        val stringRequest = StringRequest(Request.Method.GET, url,
            Response.Listener<String> { response ->
                // Display the first 500 characters of the response string.
                if(response != null){
                    if(response[used] == 1)
                    throw InvalidHealthCertificateException(HC_ZLIB_DECOMPRESSION_FAILED)
                } else {
                    throw InvalidHealthCertificateException(HC_ZLIB_DECOMPRESSION_FAILED)
                }
            },

// Add the request to the RequestQueue.
        queue.add(stringRequest)
        Timber.v("Parsing COSE for vaccination certificate.")

        val cbor = coseDecoder.decode(this)

        return VaccinationCertificateData(
            header = headerParser.parse(cbor),
            certificate = bodyParser.parse(cbor)
        ).also {
            CertificateQrCodeCensor.addCertificateToCensor(it)
        }.also {
            Timber.v("Parsed vaccination certificate for %s", it.certificate.nameData.familyNameStandardized)
        }
    }

    companion object {
        private const val PREFIX = "HC1:"

        // Zip bomb
        private const val DEFAULT_SIZE_LIMIT = 1024L * 1024 * 10L // 10 MB
    }
}
