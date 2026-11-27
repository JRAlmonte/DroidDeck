# Compose previews

Previews show production composables with sample data, without a device, the Linux runtime or a network. Use them in two
ways:

- **Android Studio:** select the `debug` build variant and open `app/src/debug/java/com/droiddeck/launcher/ui/LauncherPreviews.kt`
  in Split or Design view.
- **Rendered to PNG files**, with no Studio or device needed (also how an agent can look at a UI change):

  ```sh
  ./gradlew -PskipRust=true :app:testDebugUnitTest --tests '*PreviewRenderTest'
  ```

  Each preview is drawn with Robolectric's native graphics at handheld (960x540 dp at xhdpi, the Duo's top screen,
  1920x1080 px) and portrait (540x960 dp) sizes, into `app/build/preview-renders/{handheld,portrait}/<case>.png`. The test
  fails if a preview throws, or draws a single flat colour.

## Files

| File | What |
| --- | --- |
| `app/src/debug/.../ui/PreviewSupport.kt` | `PreviewHost` (the production theme), `@DroidDeckPreviews` (the two sizes), sample `FrontEndState`, no-op `FrontEndActions` and `DrawerActions`, sample games |
| `app/src/debug/.../ui/LauncherPreviews.kt` | The previews, and `previewCases`, the list the render test draws |
| `app/src/testDebug/.../ui/PreviewRenderTest.kt` | The render test |

Previews cover the launcher on each tab (Steam, Library, Library with no games, Stores, Downloads, Settings, Settings with
no runtime), the in-game menu's pages (Controller, Display, HUD, Session, and the Desktop menu), a RomM game's
action row before it is installed (a game nothing plays, and one with extra files to choose) and its extra-files menu, what an
installed game's row adds for extra files and that menu with a file about to be deleted, the Details rows of a RomM game's page with words and with icons,
the versions menu of a RomM game's page, the RomM store's Sort menu, its browse bar while Installed is chosen, the far end of it with what only the server answers and the bar with several platforms chosen, its platform menu with two platforms ticked and what the server says of each under its name, with words and with room for pictures, the saves and states a game's Sync tab lists from the server, that tab where the server refused the sign-in, the games under "More like this" with the reason the server gives for each, the RetroAchievements line of the RomM profile strip, its size line with the saves and states the server keeps where they fit, a note there opened to what can be done with it, the RomM store's collection menu with the row that makes one and what the bar adds for a chosen collection of the account's own, and a browse bar's dropdown with a short and a long list. A
popup is its own window and the render test draws the topmost one, so those two files show the menu alone.

## Adding one

Write the preview in `LauncherPreviews.kt` (or a new file beside it) with `@DroidDeckPreviews`, wrapping the content in
`PreviewHost`. Add a `PreviewCase` to `previewCases` so it is rendered and checked too. The launcher opens on a tab via
`sampleState(navigate = "<tab key>")`.

Everything here is in the debug source sets, so none of it ships in release builds. Sample art is left out, so cards use
their fallback gradients. Previews check layout and look; they do not exercise launching, input or the runtime.
