package com.kinonn.ocrmobile.di

import android.content.Context
import com.kinonn.ocrmobile.BuildConfig
import com.kinonn.ocrmobile.core.ocr.DemoOcrEngine
import com.kinonn.ocrmobile.core.ocr.OcrEngine
import com.kinonn.ocrmobile.core.parse.FieldExtractor
import com.kinonn.ocrmobile.ocr.PaddleLiteOcrEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideFieldExtractor(): FieldExtractor = FieldExtractor()

    @Provides
    @Singleton
    fun provideOcrEngine(@ApplicationContext context: Context): OcrEngine =
        if (BuildConfig.USE_DEMO_OCR) {
            DemoOcrEngine()
        } else {
            PaddleLiteOcrEngine(context)
        }
}
