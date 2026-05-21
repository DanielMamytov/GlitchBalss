package np.sairwv.glitchballs.launch

import org.json.JSONArray
import org.json.JSONObject

fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any> {
    val map = linkedMapOf<String, Any>()
    val iterator = jsonObject.keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        val value = jsonObject.opt(key)
        if (value != null && value != JSONObject.NULL) {
            map[key] = jsonToAny(value)
        }
    }
    return map
}

private fun jsonArrayToList(array: JSONArray): List<Any> {
    val list = mutableListOf<Any>()
    for (index in 0 until array.length()) {
        val value = array.opt(index)
        if (value != null && value != JSONObject.NULL) {
            list += jsonToAny(value)
        }
    }
    return list
}

private fun jsonToAny(value: Any): Any {
    return when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        else -> value
    }
}

fun JSONObject.putAny(key: String, value: Any?) {
    when (value) {
        null -> put(key, JSONObject.NULL)
        is JSONObject -> put(key, value)
        is JSONArray -> put(key, value)
        is Map<*, *> -> {
            val objectValue = JSONObject()
            value.forEach { (entryKey, entryValue) ->
                if (entryKey != null) {
                    objectValue.putAny(entryKey.toString(), entryValue)
                }
            }
            put(key, objectValue)
        }

        is Iterable<*> -> {
            val array = JSONArray()
            value.forEach { item ->
                array.put(anyToJson(item))
            }
            put(key, array)
        }

        is Array<*> -> {
            val array = JSONArray()
            value.forEach { item ->
                array.put(anyToJson(item))
            }
            put(key, array)
        }

        is Boolean, is Number, is String -> put(key, value)
        else -> put(key, value.toString())
    }
}

private fun anyToJson(value: Any?): Any {
    return when (value) {
        null -> JSONObject.NULL
        is JSONObject -> value
        is JSONArray -> value
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (entryKey, entryValue) ->
                if (entryKey != null) {
                    putAny(entryKey.toString(), entryValue)
                }
            }
        }

        is Iterable<*> -> JSONArray().apply {
            value.forEach { item ->
                put(anyToJson(item))
            }
        }

        is Array<*> -> JSONArray().apply {
            value.forEach { item ->
                put(anyToJson(item))
            }
        }

        is Boolean, is Number, is String -> value
        else -> value.toString()
    }
}
