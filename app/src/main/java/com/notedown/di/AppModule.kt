package com.notedown.di

import android.content.Context
import androidx.room.Room
import com.notedown.api.*
import com.notedown.data.*
import com.notedown.audio.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class) object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context) = Room.databaseBuilder(context, NotedownDatabase::class.java, "notedown.db").build()
    @Provides fun dao(database: NotedownDatabase) = database.history()
    @Provides @Singleton fun api(): OpenRouterApi = Retrofit.Builder().baseUrl("https://openrouter.ai/api/v1/")
        .client(OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build())
        .addConverterFactory(Json { ignoreUnknownKeys = true; explicitNulls = false }.asConverterFactory("application/json".toMediaType()))
        .build().create(OpenRouterApi::class.java)
    @Provides @Singleton fun recorder(@ApplicationContext context: Context): AudioRecorder = AndroidAudioRecorder(context)
}
