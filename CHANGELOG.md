# Beacon changelog

## v0.5.28

- The animated background is now drawn directly on the screen at full sharpness instead of a small picture stretched up, so it is smooth and no longer looks pixelated; the grain over it is much subtler, and long tab names (like Feedback) shrink to fit instead of being cut off

## v0.5.27

- An app you uninstall or install shows up (or goes) in Launchpad and search straight away

## v0.5.26

- Video files open in Memories (Cinema has been merged into it)

## v0.5.25

- At night the animated background gets a stronger wash, so text stays easy to read on dark scenes

## v0.5.24

- Each app's animated scene now has its own drifting symbols (maths signs in Abacus, notes in Echo, arrows in Wayfinder and so on), and the scene is a little clearer while text stays easy to read
- Animated backgrounds reworked after your report: they are drawn at a higher resolution and smoothly at 24 frames a second without slowing the app down, and no longer show horizontal lines

## v0.5.23

- Every app window now has the animated scene behind it, in the app's own colour (for example orange water in Abacus, green in Pulse, purple in Scribble), following your Aqua, Bliss or Super look and the real sunrise and sunset. Motion Reduced shows a still picture and Off turns it off

## v0.5.22

- Fixed a gap above the keyboard in screens with a bottom tab bar: the tab bar now steps aside while you type, so you can see what you are writing
- Launchpad now has the suite's animated water, bubbles and sky behind it, so it matches the theme instead of looking like a plain app drawer

## v0.5.21

- The Wonderdesk Launcher no longer appears in Beacon's app list (it is the home screen, so there is nothing to open)

## v0.5.20

- Settings are grouped under clear headings: search, and results and history

## v0.5.19

- New setting: the most file results shown in a search

## v0.5.18

- New settings: how many recent searches to remember, whether enter opens the top result, and app tile size
- Text size and accent colour chosen in Control Room now apply here
- New Rotate with the phone switch in Settings

## v0.5.17

- Loading screens now say what they are doing instead of showing a bare spinner
- Wording tidied so no sentence needs a dash

## v0.5.16

- No loose apps message reworded to two short sentences.

## v0.5.15

- New folder button in the options menu. Name it, tick the apps, done. No more figuring out drag to make a folder.
- Moving apps, renaming and deleting folders, and taking apps out now show at once. A failed folders read keeps the last good list instead of blanking the grid.

## v0.5.14

- Folder moves now show instantly: dragging into/out of folders, renames and deletes update the grid at once instead of sometimes needing a reopen
- A failed folders read no longer blanks the grid. The last good state stays until the next good read

## v0.5.13

- Folder long-press fixed: apps inside folders open their options menu like everywhere else; drag-to-move only happens in Arrange mode

## v0.5.12

- Arrange apps (⋮ menu): a move mode where you hold an app and drag it where you want it (the order is saved); outside move mode, holding an app just opens its options: add to home screen or Dock, pin, folder, hide, info, uninstall

## v0.5.11

- New setting Search contacts: stop contacts showing in results without taking the permission away
- All apps: turn “Recently used” off or on from the ⋮ menu
- All apps: pin up to 8 apps to a Pinned row at the top (hold an app › Pin to the top)
- All apps: a “My order” sort; hold an app and choose Move earlier or Move later, and the order is saved

## v0.5.10

- Recently used: the apps you opened last sit in a glass row at the top of all apps, one tap away
- Fixed a crash when moving around with arrow keys or Tab while a screen was closing

## v0.5.9

- Lighter on the battery: in the Super look the drifting sparkles settle after a few seconds without a touch (the next tap wakes them), so a screen you're reading no longer redraws 25 times a second

## v0.5.8

- Dragging an app into a folder: the grid now scrolls when you hold the app near the top or bottom edge, so every app and folder can be reached
- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.5.7

- Widgets: choose the title colour in each widget's settings (automatic, white, dark, Aqua blue, Super pink or gold), on top of the background looks

## v0.5.6

- All apps now looks like Beacon: a glass header with Beacon's badge and the search box, in your look

## v0.5.5

- Widgets can now match your look: new Bliss blue and Super neon backgrounds, and “Match my Wonderdesk look” follows whatever Control Room is set to

## v0.5.4

- Fixes from a full round of stress testing on every screen

## v0.5.3

- Drag apps into folders: hold an app and drop it on a folder to add it, or on another app to make a new folder; drag an app out of an open folder to take it out
- The long list of “Move to …” folders in the app menu is replaced by one “Add to a folder…” picker
- Swiping down to close is consistent: the page follows your finger, and a quick flick or a short pull closes it every time
- Tap the tab you're already on to glide back to the top of its list
- Switches Android greys out as a “Restricted setting” are now explained: the guide walks you through unlocking them in three clear steps, and keeps showing them until the switch is really on (before, on Android 15 and newer it could hide the only hint)
- Follows your look everywhere: viewers, panels, menus and messages use the Aqua, Bliss, Dark Bliss or Super colours
- Nothing required stays hidden: if Android is blocking something the app needs, a slim banner says what and takes you straight to the switch, and Settings › Permissions and access lists everything with its state

## v0.5.2

- Apps open by growing out of the icon you tapped
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Search widget shrinks to a single search button at small sizes
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.5.1

- Beacon's icon opens straight to all your apps with search, folders and sorting. Search settings and the file index are under ⋮
- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.5.0

- New Launchpad: every app over a dimmed, blurred wallpaper, with shared folders, six sort orders, hidden apps, and Beacon search for apps, sums, conversions, settings, contacts and files
- Hold an app in Launchpad to add it to the home screen or Dock, move it into a folder, hide it, see its info or uninstall it
- Search widget settings: dark bar, show or hide the All apps button, your own placeholder text. Tapping it opens Launchpad ready to type
- The Quick Settings tile opens Launchpad search
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.4.0

- Beacon is now also the Launchpad: an All apps tab with folders shared with the launcher and six sort orders
- Answers sums and unit conversions, opens around 45 settings pages, and finds contacts, files and the web
- Other Wonderdesk apps can use Beacon search, so search works the same everywhere
- New Quick Settings tile and a home screen search widget
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.3.1

- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.3.0

- Search apps, settings, contacts, file names and the words inside files
- Open results in Wayfinder or Scribble
- Calculator answers and web search right in the box
