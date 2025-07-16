// In: com/gemweblive/translationmodels/ModelRepository.kt

package com.gemweblive.translationmodels

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

// Helper data classes to match the JSON structure for parsing
private data class ModelGroup(val inputs: List<String>, val outputs: List<String>, val models: List<Model>)
private data class InOutFile(val input_output_groups: List<ModelGroup>)

class ModelRepository(private val context: Context) {

    private val gson = Gson()

    /**
     * Reads and parses models.json and inout.json from the assets folder,
     * combining them into a single, comprehensive list of Model objects.
     */
    fun getModels(): List<Model> {
        val allModels = mutableListOf<Model>()

        try {
            // Open the master file that contains all model parameters
            val inoutInputStream = context.assets.open("inout.json")
            val inoutData: InOutFile = gson.fromJson(
                InputStreamReader(inoutInputStream),
                object : TypeToken<InOutFile>() {}.type
            )

            // Process each group (e.g., "audio -> text", "text -> audio")
            for (group in inoutData.input_output_groups) {
                for (modelInGroup in group.models) {
                    // Create a new Model object, injecting the input/output capabilities
                    // from the group level into each individual model.
                    val completeModel = modelInGroup.copy(
                        inputs = group.inputs,
                        outputs = group.outputs
                    )
                    allModels.add(completeModel)
                }
            }
        } catch (e: Exception) {
            // In a real app, you'd want more robust error handling
            e.printStackTrace()
        }

        return allModels
    }
}
