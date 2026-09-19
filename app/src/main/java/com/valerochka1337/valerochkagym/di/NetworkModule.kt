package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.update.GitHubReleaseApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Общий HTTP-клиент и API обновлений. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

  private const val GITHUB_BASE_URL = "https://api.github.com/"

  @Provides
  @Singleton
  fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Provides @Singleton fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

  @Provides
  @Singleton
  @Named("github")
  fun provideGitHubRetrofit(client: OkHttpClient, json: Json): Retrofit =
      Retrofit.Builder()
          .baseUrl(GITHUB_BASE_URL)
          .client(client)
          .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
          .build()

  @Provides
  @Singleton
  fun provideGitHubReleaseApi(@Named("github") retrofit: Retrofit): GitHubReleaseApi =
      retrofit.create(GitHubReleaseApi::class.java)
}
