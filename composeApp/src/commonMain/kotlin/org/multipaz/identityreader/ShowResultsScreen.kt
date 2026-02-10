package org.multipaz.identityreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import multipazidentityreader.composeapp.generated.resources.Res
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.claim.MdocClaim
import org.multipaz.compose.decodeImage
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun ShowResultsScreen(
    readerQuery: ReaderQuery,
    readerModel: ReaderModel,
    documentTypeRepository: DocumentTypeRepository,
    issuerTrustManager: TrustManager,
    onBackPressed: () -> Unit,
    onShowDetailedResults: (() -> Unit)?
) {
    val coroutineScope = rememberCoroutineScope()
    val documents = remember { mutableStateOf<List<ParsedMdocDocument>?>(null) }
    val verificationError = remember { mutableStateOf<Throwable?>(null) }
    print("onShowDetailedResults: $onShowDetailedResults foo")

    LaunchedEffect(Unit) {
        if (readerModel.error == null) {
            coroutineScope.launch {
                val now = Clock.System.now()
                try {
                    documents.value =
                        parseResponse(now, readerModel, documentTypeRepository, issuerTrustManager)
                } catch (e: Throwable) {
                    Logger.e("ShowResultsScreen", "Verification error", e)
                    verificationError.value = e
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            if (readerModel.error != null) {
                ShowResultsScreenFailed(
                    message = "Something went wrong",
                    secondaryMessage = null,
                    onShowDetailedResults = onShowDetailedResults
                )
            } else {
                if (documents.value == null && verificationError.value == null) {
                    ShowResultsScreenValidating()
                } else if (verificationError.value != null) {
                    ShowResultsScreenFailed(
                        message = "Document verification failed",
                        secondaryMessage = verificationError.value?.message ?: "Unknown error",
                        onShowDetailedResults = onShowDetailedResults
                    )
                } else {
                    if (documents.value!!.size == 0) {
                        ShowResultsScreenFailed(
                            message = "No documents returned",
                            secondaryMessage = null,
                            onShowDetailedResults = onShowDetailedResults
                        )
                    } else {
                        ShowResultsScreenSuccess(
                            readerQuery = readerQuery,
                            documents = documents.value!!,
                            onShowDetailedResults = onShowDetailedResults
                        )
                    }
                }
            }
        }
    }
}



private data class ParsedMdocDocument(
    val docType: String,
    val msoValidFrom: Instant,
    val msoValidUntil: Instant,
    val msoSigned: Instant,
    val msoExpectedUpdate: Instant?,
    val namespaces: List<ParsedMdocNamespace>,
    val trustPoint: TrustPoint?
)

private data class ParsedMdocNamespace(
    val name: String,
    val dataElements: MutableMap<String, MdocClaim>
)

// Throws IllegalArgumentException if validity checks fail
//
//
private suspend fun parseResponse(
    now: Instant,
    readerModel: ReaderModel,
    documentTypeRepository: DocumentTypeRepository,
    issuerTrustManager: TrustManager
): List<ParsedMdocDocument> {
    val deviceResponseBytes = readerModel.result!!.encodedDeviceResponse!!.toByteArray()
    Logger.iCbor("ShowResultsScreen", "DeviceResponse", Cbor.decode(deviceResponseBytes))
    Logger.d("ShowResultsScreen", "DeviceResponse (Hex): ${deviceResponseBytes.toHex()}")
    println("ShowResultsScreen: DeviceResponse size: ${deviceResponseBytes.size}")

    val deviceResponse = try {
        DeviceResponse.fromDataItem(Cbor.decode(deviceResponseBytes))
    } catch (e: Throwable) {
        throw RuntimeException("Error parsing DeviceResponse: ${e.message}", e)
    }
    val sessionTranscript = Cbor.decode(readerModel.result!!.encodedSessionTranscript.toByteArray())
    Logger.iCbor("ShowResultsScreen", "SessionTranscript", sessionTranscript)
    Logger.d("ShowResultsScreen", "SessionTranscript: $sessionTranscript")
    println("ShowResultsScreen: SessionTranscript: $sessionTranscript")
    try {
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(
                privateKey = readerModel.result!!.eReaderKey,
            ),
            atTime = now
        )
    } catch (e: Throwable) {
        Logger.w("ShowResultsScreen", "Device response verification failed", e)
    }

    val readerDocuments = mutableListOf<ParsedMdocDocument>()
    for (document in deviceResponse.documents) {
        val trustResult = issuerTrustManager.verify(document.issuerCertChain.certificates, Clock.System.now())
          //require(trustResult.isTrusted)

        val mdocType = documentTypeRepository.getDocumentTypeForMdoc(document.docType)?.mdocDocumentType
        val resultNs = mutableListOf<ParsedMdocNamespace>()
        for ((namespace, items) in document.issuerNamespaces.data) {
            val resultDataElements = mutableMapOf<String, MdocClaim>()
            val mdocNamespace = if (mdocType != null) {
                mdocType.namespaces.get(namespace)
            } else {
                documentTypeRepository.getDocumentTypeForMdocNamespace(namespace)
                    ?.mdocDocumentType?.namespaces?.get(namespace)
            }
            for ((dataElement, item) in items) {
                val mdocDataElement = mdocNamespace?.dataElements?.get(dataElement)
                resultDataElements[dataElement] = MdocClaim(
                    displayName = mdocDataElement?.attribute?.displayName ?: dataElement,
                    attribute = mdocDataElement?.attribute,
                    namespaceName = namespace,
                    dataElementName = dataElement,
                    value = item.dataElementValue
                )
            }
            resultNs.add(ParsedMdocNamespace(namespace, resultDataElements))
        }
        for ((namespace, items) in document.deviceNamespaces.data) {
            val resultDataElements = mutableMapOf<String, MdocClaim>()
            val mdocNamespace = if (mdocType != null) {
                mdocType.namespaces.get(namespace)
            } else {
                documentTypeRepository.getDocumentTypeForMdocNamespace(namespace)
                    ?.mdocDocumentType?.namespaces?.get(namespace)
            }
            for ((dataElement, item) in items) {
                val mdocDataElement = mdocNamespace?.dataElements?.get(dataElement)
                resultDataElements[dataElement] = MdocClaim(
                    displayName = mdocDataElement?.attribute?.displayName ?: dataElement,
                    attribute = mdocDataElement?.attribute,
                    namespaceName = namespace,
                    dataElementName = dataElement,
                    value = item
                )
            }
            // Merge with existing namespace... this is O(n) but n is small.
            val existingNamespace = resultNs.find { it.name == namespace }
            if (existingNamespace != null) {
                existingNamespace.dataElements.putAll(resultDataElements)
            } else {
                resultNs.add(ParsedMdocNamespace(namespace, resultDataElements))
            }
        }

        readerDocuments.add(
            ParsedMdocDocument(
                docType = document.docType,
                msoValidFrom = document.mso.validFrom,
                msoValidUntil = document.mso.validUntil,
                msoSigned = document.mso.signedAt,
                msoExpectedUpdate = document.mso.expectedUpdate,
                namespaces = resultNs,
                trustPoint = trustResult.trustPoints.firstOrNull()
            )
        )
    }
    return readerDocuments
}

@Composable
private fun ShowResultsScreenValidating() {
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Validating documents",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

        }
    }
}

@Composable
private fun ShowResultsScreenFailed(
    message: String,
    secondaryMessage: String?,
    onShowDetailedResults: (() -> Unit)?
) {
    val errorComposition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/error_animation.json").decodeToString()
        )
    }
    val errorProgressState = animateLottieCompositionAsState(
        composition = errorComposition,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = rememberLottiePainter(
                    composition = errorComposition,
                    progress = { errorProgressState.value },
                ),
                contentDescription = null,
                modifier = Modifier.size(200.dp)
                    .let {
                        if (onShowDetailedResults != null) {
                            it.combinedClickable(
                                onClick = {},
                                onDoubleClick = { onShowDetailedResults() }
                            )
                        } else it
                    },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (secondaryMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = secondaryMessage,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

        }
    }
}

@Composable
private fun ShowResultsScreenSuccess(
    readerQuery: ReaderQuery,
    documents: List<ParsedMdocDocument>,
    onShowDetailedResults: (() -> Unit)?
) {
    // For now we only consider the first document...
    val document = documents[0]

    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (document.trustPoint?.metadata?.testOnly == true) {
                Text(
                    text = "TEST DATA\nDO NOT USE",
                    textAlign = TextAlign.Center,
                    lineHeight = 1.25.em,
                    color = Color(red = 255, green = 128, blue = 128, alpha = 192),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = TextStyle(
                        fontSize = 30.sp,
                        shadow = Shadow(
                            color = Color.Black,
                            offset = Offset(0f, 0f),
                            blurRadius = 2f
                        ),
                    ),
                )
            }

            when (readerQuery) {
                ReaderQuery.AGE_OVER_18 -> {
                    ShowAgeOver(
                        age = 18,
                        document = document,
                        onShowDetailedResults = onShowDetailedResults
                    )
                }

                ReaderQuery.AGE_OVER_21 -> {
                    ShowAgeOver(
                        age = 21,
                        document = document,
                        onShowDetailedResults = onShowDetailedResults
                    )
                }

                ReaderQuery.IDENTIFICATION -> {
                    ShowIdentification(
                        document = document,
                        onShowDetailedResults = onShowDetailedResults
                    )
                }

                ReaderQuery.AADHAAR_IDENTIFICATION -> {
                    ShowIdentification(
                        document = document,
                        onShowDetailedResults = onShowDetailedResults
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowAgeOver(
    age: Int,
    document: ParsedMdocDocument,
    onShowDetailedResults: (() -> Unit)?
) {
    val portraitBitmap = remember { getPortraitBitmap(document) }
    val ageOver = when (document.docType) {
        DrivingLicense.MDL_DOCTYPE -> {
            val mdlNamespace = document.namespaces.find { it.name == DrivingLicense.MDL_NAMESPACE }
            try { mdlNamespace?.dataElements?.get("age_over_${age}")?.value?.asBoolean } catch (e: Throwable) { null }
        }
        PhotoID.PHOTO_ID_DOCTYPE -> {
            val iso23220Namespace = document.namespaces.find { it.name == PhotoID.ISO_23220_2_NAMESPACE }
            try { iso23220Namespace?.dataElements?.get("age_over_${age}")?.value?.asBoolean } catch (e: Throwable) { null }
        }
        "in.gov.uidai.aadhaar.1" -> {
            val aadhaarNamespace = document.namespaces.find { it.name == "in.gov.uidai.aadhaar.1" }
            if (age == 18) {
                try { aadhaarNamespace?.dataElements?.get("age_over_18")?.value?.asBoolean } catch (e: Throwable) { null }
            } else {
                null
            }
        }
        else -> null
    }

    val (message, animationFile) = if (ageOver != null && ageOver == true) {
        Pair("This person is $age or older", "files/success_animation.json")
    } else if (ageOver != null) {
        Pair("This person is NOT $age or older", "files/error_animation.json")
    } else {
        Pair("Unable to determine if this person is $age or older", "files/error_animation.json")
    }

    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes(animationFile).decodeToString()
        )
    }
    val progressState = animateLottieCompositionAsState(
        composition = composition,
    )

    portraitBitmap?.let { bitmap ->
        Image(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp).padding(16.dp)
                .let {
                    println("onShowDetailedResults: $onShowDetailedResults")
                    if (onShowDetailedResults != null) {
                        it.combinedClickable(
                            onClick = {},
                            onDoubleClick = { onShowDetailedResults() }
                        )
                    } else it
                },
            bitmap = bitmap,
            contentDescription = null
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberLottiePainter(
                composition = composition,
                progress = { progressState.value },
            ),
            contentDescription = null,
            modifier = Modifier.size(50.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ShowIdentification(
    document: ParsedMdocDocument,
    onShowDetailedResults: (() -> Unit)?
) {
    val portraitBitmap = remember { getPortraitBitmap(document) }
    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/success_animation.json").decodeToString()
        )
    }
    val progressState = animateLottieCompositionAsState(
        composition = composition,
    )

    portraitBitmap?.let { bitmap ->
        Image(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(16.dp)
                .let {
                    if (onShowDetailedResults != null) {
                        it.combinedClickable(
                            onClick = {},
                            onDoubleClick = { onShowDetailedResults() }
                        )
                    } else it
                },
            bitmap = bitmap,
            contentDescription = null
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberLottiePainter(
                composition = composition,
                progress = { progressState.value },
            ),
            contentDescription = null,
            modifier = Modifier.size(50.dp)
        )
        Text(
            text = "Identity data verified",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape = RoundedCornerShape(8.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (namespace in document.namespaces) {
            for ((dataElementName, dataElement) in namespace.dataElements) {
                val key = if (dataElement.attribute != null) {
                    dataElement.attribute!!.displayName
                } else {
                    dataElementName
                }
                val value = dataElement.render(TimeZone.currentSystemDefault())

                if (namespace.name == DrivingLicense.MDL_NAMESPACE && dataElementName == "portrait") {
                    continue
                }
                if (namespace.name == PhotoID.ISO_23220_2_NAMESPACE && dataElementName == "portrait") {
                    continue
                }
                if (namespace.name == "in.gov.uidai.aadhaar.1" && dataElementName == "resident_image") {
                    continue
                }

                KeyValuePairText(key, value)
            }
        }
    }
}

@Composable
private fun KeyValuePairText(
    keyText: String,
    valueText: String
) {
    Column(
        Modifier
            .padding(8.dp)
            .fillMaxWidth()) {
        Text(
            text = keyText,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun getPortraitBitmap(document: ParsedMdocDocument): ImageBitmap? {
    when (document.docType) {
        DrivingLicense.MDL_DOCTYPE -> {
            val mdlNamespace = document.namespaces.find { it.name == DrivingLicense.MDL_NAMESPACE }
            if (mdlNamespace == null) {
                return null
            }
            val portraitClaim = mdlNamespace.dataElements["portrait"]
            if (portraitClaim == null) {
                return null
            }
            val value = portraitClaim.value
            try {
                return decodeImage(value.asBstr)
            } catch (e: Throwable) {
                try {
                    val base64 = (value as org.multipaz.cbor.Tstr).value
                    return decodeImage(base64.fromBase64Url())
                } catch (e2: Throwable) {
                    return null
                }
            }
        }
        PhotoID.PHOTO_ID_DOCTYPE -> {
            val iso23220Namespace = document.namespaces.find { it.name == PhotoID.ISO_23220_2_NAMESPACE }
            if (iso23220Namespace == null) {
                return null
            }
            val portraitClaim = iso23220Namespace.dataElements["portrait"]
            if (portraitClaim == null) {
                return null
            }
            val value = portraitClaim.value
            try {
                return decodeImage(value.asBstr)
            } catch (e: Throwable) {
                try {
                    val base64 = (value as org.multipaz.cbor.Tstr).value
                    return decodeImage(base64.fromBase64Url())
                } catch (e2: Throwable) {
                    return null
                }
            }
        }
        "in.gov.uidai.aadhaar.1" -> {
            val aadhaarNamespace = document.namespaces.find { it.name == "in.gov.uidai.aadhaar.1" }
            if (aadhaarNamespace == null) {
                return null
            }
            val portraitClaim = aadhaarNamespace.dataElements["resident_image"]
            if (portraitClaim == null) {
                return null
            }
            val value = portraitClaim.value
            try {
                return decodeImage(value.asBstr)
            } catch (e: Throwable) {
                try {
                    // Try to see if it's a Tstr containing Base64
                    val base64 = (value as org.multipaz.cbor.Tstr).value
                    // The error "Invalid symbol '/'" suggests standard Base64 is used, but fromBase64Url expects URL-safe.
                    // Convert standard to URL-safe: + -> -, / -> _
                    val urlSafeBase64 = base64.replace('+', '-').replace('/', '_')
                    return decodeImage(urlSafeBase64.fromBase64Url())
                } catch (e2: Throwable) {
                    Logger.e("ShowResultsScreen", "Failed to decode portrait for Aadhaar: $e, $e2")
                    return null
                }
            }
        }
        else -> {
            return null
        }
    }
}

@Composable
private fun ShowResultDocument(
    document: ParsedMdocDocument,
) {
    val portraitBitmap = remember { getPortraitBitmap(document) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .verticalScroll(scrollState)
            .padding(8.dp),
    ) {
        if (portraitBitmap != null) {
            Image(
                modifier = Modifier.fillMaxWidth().height(300.dp).padding(16.dp),
                bitmap = portraitBitmap,
                contentDescription = null
            )
        }

        for (namespace in document.namespaces) {
            for ((dataElementName, dataElement) in namespace.dataElements) {
                val key = if (dataElement.attribute != null) {
                    dataElement.attribute!!.displayName
                } else {
                    dataElementName
                }
                val value = dataElement.render(TimeZone.currentSystemDefault())

                if (portraitBitmap != null && namespace.name == DrivingLicense.MDL_NAMESPACE && dataElementName == "portrait") {
                    continue
                }

                KeyValuePairText(key, value)
            }
        }
    }
}
