# Get a Red TV APK in ~3 minutes (no Android Studio)

GitHub builds the app for you in the cloud and hands you the finished APK.
You only need a free GitHub account and a web browser.

## One-time setup
1. Go to https://github.com and sign up / log in (free).
2. Click the **+** (top right) > **New repository**.
   - Name it `redtv` (anything works). Set it **Private** if you like. Click **Create repository**.
3. On the new repo page, click **uploading an existing file** (the link in the
   "Quick setup" box). Then drag the **contents of the RedTV folder** into the page
   (select everything inside RedTV and drag it in, including the `.github` folder and
   the `app` folder). Wait for all files to list, then click **Commit changes**.

   Tip: if dragging the `.github` folder is awkward, that's the only file that matters
   for building - make sure `.github/workflows/build-apk.yml` ends up in the repo.

## Get the APK
4. Click the **Actions** tab at the top of your repo. You'll see a run called
   **Build Red TV APK** (it starts automatically after the upload). Click it.
5. Wait for the green check (about 3 minutes the first time).
6. On that run's page, scroll to **Artifacts** at the bottom and download
   **RedTV-debug-apk**. It downloads as a .zip - unzip it to get **app-debug.apk**.

## Put it on the Firestick
- Upload `app-debug.apk` to Dropbox/Google Drive, get a direct link, and open it in the
  **Downloader** app on the Firestick (enable Settings > My Fire TV > Developer Options >
  Install unknown apps > Downloader first).
- Or with the Firestick's ADB on (Developer Options > ADB debugging):
  `adb connect <firestick-ip>:5555` then `adb install -r app-debug.apk`.

## Updating later
Any time the code changes (or you click **Run workflow** on the Actions tab), GitHub
builds a fresh APK automatically. Download the newest artifact and reinstall.
