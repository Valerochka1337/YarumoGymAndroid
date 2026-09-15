package com.valerochka1337.valerochkagym.ui.analysis.body

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import kotlin.math.min

/** Соотношение сторон фигуры: вьюпорт библиотеки 724×1448, то есть строго 1:2. */
private const val BODY_ASPECT = 0.5f

/** Вид сзади в SVG сдвинут на ширину вьюпорта вправо — возвращаем его в 0..724. */
private const val BACK_X_OFFSET = -724f

// Толщины штрихов в координатах вьюпорта (724 в ширину). Рисуются внутри scale, поэтому
// заданы «крупно»: при типичной ширине карты масштаб около 0.5, и значения вдвое мельче в px.
private const val OUTLINE_STROKE = 3f
private const val SEPARATION_STROKE = 4.5f
private const val SELECTION_OUTER_STROKE = 13f
private const val SELECTION_INNER_STROKE = 7f

/**
 * Одна фигура тела с переключением спереди/сзади по кнопке-развороту в углу.
 *
 * Пришла на смену паре фигур рядом: анатомически достоверный силуэт из
 * react-native-body-highlighter крупнее и детальнее, и две такие фигуры сразу не помещаются на
 * карту с пользой. Разворот — это [Crossfade] между видами; общая раскраска ([fillFor]) и выбор
 * ([selectedMuscle]) сохраняются при перевороте.
 *
 * Часть мышц ([offFigureMuscles]) на фигуре не выделяется отдельной областью — рисунок их не
 * разделяет. Их выбирают из списков (карточки объёма и частоты) или из списка в редакторе.
 */
@Composable
fun BodyMapFlip(
    fillFor: (MuscleSector) -> Color,
    modifier: Modifier = Modifier,
    selectedMuscle: Muscle? = null,
    onMuscleClick: ((Muscle?) -> Unit)? = null,
    initialView: BodyView = BodyView.FRONT,
    figureWidthFraction: Float = 0.56f,
    showViewLabel: Boolean = true,
) {
  var view by remember { mutableStateOf(initialView) }
  LaunchedEffect(selectedMuscle) { selectedMuscle?.let(::preferredBodyView)?.let { view = it } }

  Column(
      modifier = modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(modifier = Modifier.fillMaxWidth(figureWidthFraction)) {
      Crossfade(
          targetState = view,
          animationSpec = GymMotion.effectsDefault(),
          label = "body-view",
      ) { current ->
        BodyMap(
            view = current,
            fillFor = fillFor,
            selectedMuscle = selectedMuscle,
            onMuscleClick = onMuscleClick,
            modifier = Modifier.fillMaxWidth(),
        )
      }
      IconButton(
          onClick = { view = if (view == BodyView.FRONT) BodyView.BACK else BodyView.FRONT },
          modifier = Modifier.align(Alignment.TopEnd),
      ) {
        Icon(
            imageVector = Icons.Rounded.Cached,
            contentDescription = "Повернуть фигуру",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (showViewLabel) {
      Spacer(Modifier.height(6.dp))
      Text(
          text = view.title(),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 2.dp),
      )
    }
  }
}

/**
 * Интерактивная карта тела одного вида: силуэт с закрашенными областями мышц и выбором тапом.
 *
 * Геометрия — SVG-контуры из BodyPaths, разобранные [PathParser] в [Path]. Раскраска вынесена в
 * [fillFor], поэтому один компонент обслуживает и тепловую карту нагрузки, и разметку мышц при
 * создании упражнения. Соседние области разделяются не рамкой, а зазором цветом подложки — рамка
 * вокруг каждой мышцы добавила бы «чернил» без данных. Обводка зарезервирована за выбранной мышцей
 * ([selectedMuscle]).
 *
 * Тап вне любой области отдаёт `null` — осознанный сброс выбора.
 */
@Composable
fun BodyMap(
    view: BodyView,
    fillFor: (MuscleSector) -> Color,
    modifier: Modifier = Modifier,
    selectedMuscle: Muscle? = null,
    onMuscleClick: ((Muscle?) -> Unit)? = null,
) {
  val parsed = remember(view) { ParsedBody.of(view) }
  val haptics = gymHaptics()
  val body = MaterialTheme.colorScheme.surfaceContainerHighest
  val surface = MaterialTheme.colorScheme.surfaceContainerHigh
  val selectedOutline = MaterialTheme.colorScheme.onSurface
  val outline = MaterialTheme.colorScheme.outline
  val accessibleMuscles =
      remember(parsed) { parsed.sectors.flatMap { it.sector.members }.distinct() }

  Canvas(
      modifier =
          modifier
              .aspectRatio(BODY_ASPECT)
              .semantics {
                contentDescription = "Карта тела, ${view.title().lowercase()}"
                stateDescription =
                    selectedMuscle?.let { "Выбрано: ${it.displayName()}" } ?: "Мышца не выбрана"
                if (onMuscleClick != null) {
                  customActions =
                      accessibleMuscles.map { muscle ->
                        CustomAccessibilityAction("Выбрать ${muscle.displayName()}") {
                          haptics.tap()
                          onMuscleClick(muscle)
                          true
                        }
                      } +
                          CustomAccessibilityAction("Сбросить выбор") {
                            onMuscleClick(null)
                            true
                          }
                }
              }
              .then(
                  if (onMuscleClick == null) {
                    Modifier
                  } else {
                    Modifier.pointerInput(parsed) {
                      detectTapGestures { offset ->
                        val muscle =
                            parsed.muscleAt(
                                offset,
                                size.width.toFloat(),
                                size.height.toFloat(),
                                selectedMuscle,
                            )
                        // Отбиваем только попадание в мышцу: тап мимо — осознанный сброс без
                        // отклика.
                        if (muscle != null) haptics.tap()
                        onMuscleClick(muscle)
                      }
                    }
                  },
              ),
  ) {
    val scale = min(size.width / parsed.viewportW, size.height / parsed.viewportH)
    val dx = (size.width - parsed.viewportW * scale) / 2f
    val dy = (size.height - parsed.viewportH * scale) / 2f

    translate(dx, dy) {
      scale(scale, scale, pivot = Offset.Zero) {
        drawPath(parsed.silhouette, color = body)
        drawPath(
            parsed.silhouette,
            color = outline.copy(alpha = 0.45f),
            style = Stroke(OUTLINE_STROKE),
        )

        parsed.sectors.forEach { shape ->
          val color = fillFor(shape.sector)
          shape.paths.forEach { path ->
            drawPath(path, color = color)
            // Зазор цветом подложки: соседние мышцы соприкасаются краями.
            drawPath(path, color = body, style = Stroke(SEPARATION_STROKE, join = StrokeJoin.Round))
          }
        }

        parsed.sectors
            .filter { selectedMuscle in it.sector.members }
            .forEach { shape ->
              shape.paths.forEach { path ->
                drawPath(
                    path,
                    color = surface,
                    style = Stroke(SELECTION_OUTER_STROKE, join = StrokeJoin.Round),
                )
                drawPath(
                    path,
                    color = selectedOutlineColorFor(selectedMuscle, shape.sector, selectedOutline),
                    style = Stroke(SELECTION_INNER_STROKE, join = StrokeJoin.Round),
                )
              }
            }
      }
    }
  }
}

/**
 * The shared-sector fill and the selected logical member's outline intentionally resolve
 * separately.
 */
internal fun selectedOutlineColorFor(
    selectedMuscle: Muscle?,
    sector: MuscleSector,
    onSurface: Color,
): Color = if (selectedMuscle in sector.members) onSurface else Color.Unspecified

private fun BodyView.title(): String = if (this == BodyView.FRONT) "Спереди" else "Сзади"

/** Одна область карты: мышца, её контуры для отрисовки и регион для попадания тапом. */
internal class MuscleSectorShape(
    val sector: MuscleSector,
    val paths: List<Path>,
    val region: Region,
    val area: Float,
)

/**
 * Разобранная фигура вида: силуэт, области мышц и вьюпорт. Разбор [PathParser] и построение
 * регионов делаются один раз на вид и кэшируются в `remember`.
 *
 * `internal`, а не `private`: тест попаданий работает с реальным разбором путей, а не с копией его
 * логики.
 */
internal class ParsedBody(
    val silhouette: Path,
    val sectors: List<MuscleSectorShape>,
    val viewportW: Float,
    val viewportH: Float,
) {
  /** Мышца под точкой канвы или `null`. */
  fun muscleAt(offset: Offset, width: Float, height: Float, selected: Muscle? = null): Muscle? {
    val scale = min(width / viewportW, height / viewportH)
    if (scale <= 0f) return null
    val dx = (width - viewportW * scale) / 2f
    val dy = (height - viewportH * scale) / 2f
    return muscleAt(((offset.x - dx) / scale).toInt(), ((offset.y - dy) / scale).toInt(), selected)
  }

  /**
   * Мышца в точке координат вьюпорта или `null`. Области проверяются от меньшей к большей: крупный
   * контур бедра иначе перекрыл бы узкие приводящие, лежащие внутри его габаритов.
   */
  fun muscleAt(vx: Int, vy: Int, selected: Muscle? = null): Muscle? =
      sectors.firstOrNull { it.region.contains(vx, vy) }?.sector?.memberForTap(selected)

  companion object {
    /**
     * Кэш на процесс: разбор ~40 SVG-путей и построение регионов занимают заметное время на главном
     * потоке, а фигура неизменна — нет смысла разбирать её заново после каждого переворота
     * Crossfade или возврата на экран. Доступ только из композиции (main).
     */
    private val cache = mutableMapOf<BodyView, ParsedBody>()

    fun of(view: BodyView): ParsedBody = cache.getOrPut(view) { build(view) }

    private fun build(view: BodyView): ParsedBody {
      val backOffset = view == BodyView.BACK
      val silhouette = silhouetteOf(view, backOffset)
      val sectors =
          muscleSectors(view)
              .map { sector ->
                val paths = sectorPaths(view, sector).map { parse(it, backOffset) }
                val region = regionOf(paths)
                MuscleSectorShape(sector, paths, region, boundsArea(paths))
              }
              .sortedBy { it.area }
      val size = viewportSize(view)
      return ParsedBody(silhouette, sectors, size.width, size.height)
    }

    /**
     * Силуэт как одна фигура: контур объединён с головой и волосами. Объединение, а не несколько
     * заливок подряд, — иначе обводка прочертила бы швы по линии волос и по челюсти.
     */
    private fun silhouetteOf(view: BodyView, backOffset: Boolean): Path =
        silhouettePaths(view)
            .map { parse(it, backOffset) }
            .reduce { acc, part -> Path().apply { op(acc, part, PathOperation.Union) } }

    private fun parse(d: String, backOffset: Boolean): Path {
      val path = PathParser().parsePathString(d).toPath()
      if (backOffset) path.asAndroidPath().offset(BACK_X_OFFSET, 0f)
      return path
    }

    /** Регион из объединения контуров: для попаданий, а не для отрисовки. */
    private fun regionOf(paths: List<Path>): Region {
      val region = Region()
      paths.forEach { path ->
        val android = path.asAndroidPath()
        val bounds = RectF()
        android.computeBounds(bounds, true)
        val clip = Rect()
        bounds.roundOut(clip)
        region.op(Region().apply { setPath(android, Region(clip)) }, Region.Op.UNION)
      }
      return region
    }

    private fun boundsArea(paths: List<Path>): Float {
      val union = RectF()
      paths.forEachIndexed { index, path ->
        val bounds = RectF()
        path.asAndroidPath().computeBounds(bounds, true)
        if (index == 0) union.set(bounds) else union.union(bounds)
      }
      return union.width() * union.height()
    }
  }
}
