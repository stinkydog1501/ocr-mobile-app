package com.kinonn.ocrmobile.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kinonn.ocrmobile.core.model.ParsedDocument
import com.kinonn.ocrmobile.ui.capture.CaptureScreen
import com.kinonn.ocrmobile.ui.review.ReviewScreen
import kotlinx.serialization.json.Json

object Routes {
    const val CAPTURE = "capture"
    const val REVIEW = "review/{documentJson}"
    const val REVIEW_ARG = "documentJson"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.CAPTURE,
    ) {
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onDocumentReady = { document ->
                    val json = Uri.encode(Json.encodeToString(document))
                    navController.navigate("review/$json")
                },
            )
        }
        composable(
            route = Routes.REVIEW,
            arguments = listOf(navArgument(Routes.REVIEW_ARG) { type = NavType.StringType }),
        ) {
            ReviewScreen(onRetake = { navController.popBackStack() })
        }
    }
}
