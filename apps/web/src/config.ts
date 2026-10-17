/**
 * Application-wide configuration and download targets
 */

// Default APK download link for the SWAR Android app.
// Update this URL with your direct link (e.g., Google Drive, GitHub Releases, Dropbox, Firebase, S3)
// or override via VITE_APK_DOWNLOAD_URL in your .env file.
export const DEFAULT_APK_DOWNLOAD_URL: string =
  (import.meta.env.VITE_APK_DOWNLOAD_URL as string) ||
  '/swar-debug.apk';

