package com.krishiradar.app.rag

import com.krishiradar.app.inference.ClassificationResult

object PromptBuilder {

    // ── Conversation turn stored in state ─────────────────────────────────────
    data class ConversationTurn(
        val displayQuery: String,       // shown in UI
        val fullUserMessage: String,    // sent to LLM (first turn includes system context)
        val assistantMessage: String
    )

    // ── First-turn user message (includes all system context) ─────────────────
    fun buildFirstTurnUserMessage(
        farmerQuery: String,
        classificationResult: ClassificationResult?,
        retrievedChunks: List<RetrievedChunk>,
        languageCode: String = "en",
        hasImage: Boolean = false,
        isImageUnidentified: Boolean = false
    ): String {
        val langInstruction = langInstruction(languageCode)
        val knowledgeBlock = knowledgeBlock(retrievedChunks)

        return buildString {
            appendLine("You are an expert agricultural advisor for Indian coconut farmers. $langInstruction. Keep pesticide and scientific names in English.")
            appendLine("Answer ONLY using the knowledge base provided below. Do not use any information outside of it.")
            appendLine()
            when {
                classificationResult != null -> {
                    if (classificationResult.diseaseLabel == "Healthy") {
                        appendLine("The plant appears healthy (${(classificationResult.confidence * 100).toInt()}% confidence).")
                        appendLine("Use the knowledge base to confirm signs of a healthy plant and provide any relevant care or prevention tips.")
                    } else {
                        appendLine("Identified condition: ${classificationResult.displayLabel} (${(classificationResult.confidence * 100).toInt()}% confidence).")
                        appendLine("Use the knowledge base to explain the cause, visible symptoms, and treatment steps.")
                    }
                }
                hasImage && isImageUnidentified -> {
                    appendLine("The farmer has shared an image. The automatic classifier could not identify it with confidence.")
                    appendLine("Look at the image carefully and respond based on what you observe:")
                    appendLine("- If it shows a coconut plant (leaf, stem, trunk, or fruit): examine it for disease, pest damage, nutrient deficiency, or healthy growth, and use the knowledge base below to advise.")
                    appendLine("- If it is not a coconut plant: briefly describe what you see and mention you can only advise on coconut plant health.")
                    appendLine("- If you cannot clearly identify the content of the image: say 'An image was shared but I could not clearly identify what it shows. Please describe the issue or retake a clearer photo.'")
                    appendLine("Do not say that no image was provided — an image was shared by the farmer.")
                }
                else -> {}
            }
            appendLine()
            if (retrievedChunks.isEmpty()) {
                appendLine("KNOWLEDGE BASE: No relevant knowledge found for this query.")
                appendLine("Rely on the image analysis only. If you cannot determine the disease, advise the farmer to consult a local agricultural officer.")
            } else {
                appendLine("KNOWLEDGE BASE (use this as your only source — do not add anything not present here):")
                appendLine(knowledgeBlock)
            }
            appendLine()
            appendLine("FARMER'S QUESTION: $farmerQuery")
            appendLine()
            appendLine("If the image shows a coconut plant: answer in 3-4 lines using only the knowledge base above, covering the observed condition, key visible signs, and recommended care steps.")
            appendLine("If the image does not show a coconut plant or you are unsure: briefly say what you observe in 1-2 lines.")
            append("ANSWER:")
        }
    }

    // ── Full multi-turn prompt (history + new question) ───────────────────────
    fun buildConversationPrompt(
        history: List<ConversationTurn>,
        currentQuery: String,
        languageCode: String = "en"
    ): String {
        return buildString {
            for (turn in history) {
                append("<start_of_turn>user\n")
                append(turn.fullUserMessage)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
                append(turn.assistantMessage)
                append("<end_of_turn>\n")
            }
            append("<start_of_turn>user\n")
            append(currentQuery)
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    fun langInstruction(languageCode: String): String = when (languageCode) {
        "ta" -> "Respond in Tamil. Use natural conversational Tamil."
        "hi" -> "Respond in Hindi."
        "kn" -> "Respond in Kannada."
        "te" -> "Respond in Telugu."
        "ml" -> "Respond in Malayalam. Use natural conversational Malayalam."
        else -> "Respond in clear, simple English."
    }

    fun knowledgeBlock(retrievedChunks: List<RetrievedChunk>): String =
        if (retrievedChunks.isEmpty()) "No specific knowledge found for this query."
        else retrievedChunks.joinToString("\n\n") { rc ->
            "[Source: ${rc.chunk.sourceName} | Section: ${rc.chunk.section}]\n${rc.chunk.text}"
        }

    fun diseaseToTopicId(diseaseLabel: String): String? = when (diseaseLabel) {
        "BudRot", "BudRootDropping" -> "bud_rot"
        "StemBleeding" -> "stem_bleeding"
        "Healthy" -> null
        else -> "disease_index"
    }

    fun defaultQuery(languageCode: String): String = when (languageCode) {
        "ta" -> "இந்த நோய்க்கான காரணம் மற்றும் சிகிச்சை என்ன?"
        "hi" -> "इस बीमारी का कारण और उपचार क्या है?"
        "kn" -> "ಈ ರೋಗದ ಕಾರಣ ಮತ್ತು ಚಿಕಿತ್ಸೆ ಏನು?"
        "te" -> "ఈ వ్యాధికి కారణం మరియు చికిత్స ఏమిటి?"
        "ml" -> "ഈ രോഗത്തിന്റെ കാരണവും ചികിത്സയും എന്താണ്?"
        else -> "What is the cause and treatment for this?"
    }

    fun nonCoconutMessage(languageCode: String): String = when (languageCode) {
        "ta" -> "இது தேங்காய் செடியின் படம் இல்லை. நோய் கண்டறிதலுக்கு தேங்காய் இலை, தண்டு அல்லது பழத்தின் கிளோஸ்-அப் படம் எடுக்கவும்."
        "hi" -> "यह नारियल के पौधे की तस्वीर नहीं लगती। बीमारी पहचानने के लिए कृपया नारियल की पत्ती, तना या फल की क्लोज़-अप फोटो लें।"
        "kn" -> "ಇದು ತೆಂಗಿನ ಸಸ್ಯದ ಚಿತ್ರವಲ್ಲ. ರೋಗ ಪತ್ತೆಗಾಗಿ ತೆಂಗಿನ ಎಲೆ, ಕಾಂಡ ಅಥವಾ ಹಣ್ಣಿನ ಕ್ಲೋಸ್-ಅಪ್ ಫೋಟೋ ತೆಗೆಯಿರಿ."
        "te" -> "ఇది కొబ్బరి మొక్క యొక్క చిత్రం కాదు. వ్యాధి నిర్ధారణ కోసం కొబ్బరి ఆకు, కాండం లేదా పండు యొక్క క్లోజప్ ఫోటో తీయండి."
        "ml" -> "ഇത് ഒരു തെങ്ങ് ചെടിയുടെ ചിത്രമല്ല. രോഗ നിർണ്ണയത്തിനായി തെങ്ങ് ഇല, തണ്ട് അല്ലെങ്കിൽ ഫലത്തിന്റെ ക്ലോസ്-അപ്പ് ഫോട്ടോ എടുക്കുക."
        else -> "This does not appear to be a coconut plant image. Please take a close-up photo of a coconut leaf, stem, or fruit for disease diagnosis."
    }

    fun lowConfidenceMessage(languageCode: String): String = when (languageCode) {
        "ta" -> "படத்தில் நோயை தெளிவாக கண்டறிய முடியவில்லை. தயவுசெய்து நேரடி சூரிய ஒளியில் இலையை நெருங்கி படம் எடுக்கவும்."
        "hi" -> "तस्वीर में बीमारी स्पष्ट रूप से पहचानी नहीं गई। कृपया पत्ती को सीधी धूप में क्लोज़-अप में फोटो लें।"
        "kn" -> "ಚಿತ್ರದಲ್ಲಿ ರೋಗವನ್ನು ಸ್ಪಷ್ಟವಾಗಿ ಗುರುತಿಸಲಾಗಲಿಲ್ಲ. ದಯವಿಟ್ಟು ನೇರ ಬೆಳಕಿನಲ್ಲಿ ಎಲೆಯ ಕ್ಲೋಸ್-ಅಪ್ ಫೋಟೋ ತೆಗೆಯಿರಿ."
        "te" -> "చిత్రంలో వ్యాధిని స్పష్టంగా గుర్తించలేకపోయాం. దయచేసి ప్రత్యక్ష సూర్యకాంతిలో ఆకు క్లోజప్ ఫోటో తీయండి."
        "ml" -> "ചിത്രത്തിൽ നിന്ന് രോഗം വ്യക്തമായി തിരിച്ചറിയാൻ കഴിഞ്ഞില്ല. നേരിട്ടുള്ള സൂര്യപ്രകാശത്തിൽ ഇലയുടെ ക്ലോസ്-അപ്പ് ഫോട്ടോ എടുക്കുക."
        else -> "Disease could not be identified clearly from the image. Please take a close-up photo of the leaf in direct sunlight."
    }

    // Legacy — kept for any callers that still use the old signature
    fun buildDiagnosisPrompt(
        farmerQuery: String,
        diseaseLabel: String,
        displayLabel: String,
        confidence: Float,
        retrievedChunks: List<RetrievedChunk>,
        languageCode: String = "en"
    ): String {
        val body = buildFirstTurnUserMessage(
            farmerQuery = farmerQuery,
            classificationResult = ClassificationResult(
                diseaseLabel = diseaseLabel,
                displayLabel = displayLabel,
                confidence = confidence,
                tier = com.krishiradar.app.inference.ConfidenceTier.HIGH,
                alternatives = emptyList()
            ),
            retrievedChunks = retrievedChunks,
            languageCode = languageCode,
            hasImage = true,
            isImageUnidentified = false
        )
        return "<start_of_turn>user\n$body<end_of_turn>\n<start_of_turn>model\n"
    }
}
