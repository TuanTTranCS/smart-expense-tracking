package com.hugo.smartexpense.app.modelprofile.data

import androidx.room.TypeConverter
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat

class ModelProfileTypeConverters {
    @TypeConverter fun inputModeToString(value: RemoteInputMode): String = value.name
    @TypeConverter fun stringToInputMode(value: String): RemoteInputMode = RemoteInputMode.valueOf(value)
    @TypeConverter fun outputFormatToString(value: RemoteStructuredOutputFormat): String = value.name
    @TypeConverter fun stringToOutputFormat(value: String): RemoteStructuredOutputFormat =
        RemoteStructuredOutputFormat.valueOf(value)
}
