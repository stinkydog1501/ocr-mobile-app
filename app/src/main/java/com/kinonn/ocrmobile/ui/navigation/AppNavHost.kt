package com.kinonn.ocrmobile.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kinonn.ocrmobile.ui.capture.CaptureScreen
import com.kinonn.ocrmobile.ui.edit.EditScreen
import com.kinonn.ocrmobile.ui.review.ReviewScreen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Routes {
    const val CAPTURE = "capture"
    const val EDIT = "edit"
    const val REVIEW = "review/{documentJson}/{imagePath}/{blocksJson}"
    const val REVIEW_ARG_DOC = "documentJson"
    const val REVIEW_ARG_IMAGE = "imagePath"
    const val REVIEW_ARG_BLOCKS = "blocksJson"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.CAPTURE,
    ) {
        composable(Routes.CAPTURE) {
            CaptureScreen(onReadyForEdit = { navController.navigate(Routes.EDIT) })
        }
        composable(Routes.EDIT) {
            EditScreen(
                onDone = { navController.popBackStack() },
                onReview = { event ->
                    val doc = Uri.encode(Json.encodeToString(event.document))
                    val image = Uri.encode(event.imagePath)
                    val blocks = Uri.encode(Json.encodeToString(event.blocks))
                    navController.navigate("review/$doc/$image/$blocks")
                },
            )
        }
        composable(
            route = Routes.REVIEW,
            arguments = listOf(
                navArgument(Routes.REVIEW_ARG_DOC) { type = NavType.StringType },
                navArgument(Routes.REVIEW_ARG_IMAGE) { type = NavType.StringType },
                navArgument(Routes.REVIEW_ARG_BLOCKS) { type = NavType.StringType },
            ),
        ) {
            ReviewScreen(onRetake = {
                // "Retake" means a fresh photo: pop back to Capture, not the
                // previous Edit step (which keeps the current image).
                navController.popBackStack(Routes.CAPTURE, inclusive = false)
            })
        }
    }
}
