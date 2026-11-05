package com.karoo_rpe.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.RemoteViews
import com.karoo_rpe.R
import com.karoo_rpe.RateRideActivity
import com.karoo_rpe.data.RideRatingRepository
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * In-ride field that shows the current rating ("7 · Good", or "Tap to rate"). Tapping it opens
 * [RateRideActivity]. Thin: rendering and the tap intent only.
 */
class RideRatingDataType(
    private val repository: RideRatingRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    override fun startStream(emitter: Emitter<StreamState>) {
        // Graphical field; the raw-value channel is unused.
        emitter.onNext(StreamState.NotAvailable)
        emitter.setCancellable { }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false, formatDataTypeId = null))

        if (config.preview) {
            emitter.updateView(render(context, RideRating(7, Feel.GOOD), config))
            return
        }

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            repository.ratingFlow.collect { emitter.updateView(render(context, it, config)) }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(context: Context, rating: RideRating, config: ViewConfig): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.field_rating)
        val rated = rating.rpe != null
        rv.setInt(
            R.id.rating_root,
            "setBackgroundResource",
            if (config.boundariesEnabled) R.drawable.bg_field_rounded else R.drawable.bg_field_square,
        )
        rv.setColorStateList(
            R.id.rating_root,
            "setBackgroundTintList",
            ColorStateList.valueOf(if (rated) COLOR_RATED else COLOR_UNRATED),
        )
        val hGravity = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> Gravity.START
            ViewConfig.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
            ViewConfig.Alignment.RIGHT -> Gravity.END
        }
        rv.setInt(R.id.rating_title, "setGravity", hGravity)
        rv.setInt(R.id.rating_value, "setGravity", hGravity or Gravity.CENTER_VERTICAL)
        rv.setTextViewText(R.id.rating_title, context.getString(R.string.field_title))
        rv.setTextViewText(R.id.rating_value, rating.summary() ?: context.getString(R.string.tap_to_rate))

        if (!config.preview) {
            val intent = Intent(context, RateRideActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.rating_root, pi)
        }
        return rv
    }

    companion object {
        const val TYPE_ID = "ride_rating"
        private const val REQUEST_CODE = 201
        private const val COLOR_UNRATED = 0xFFE65100.toInt() // orange: needs attention
        private const val COLOR_RATED = 0xFF37474F.toInt() // neutral blue-grey
    }
}
