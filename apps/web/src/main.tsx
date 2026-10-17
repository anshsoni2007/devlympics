import React, { useState, useEffect, lazy, Suspense } from "react";
import ReactDOM from "react-dom/client";
import { PixelTransceiver } from "./components/PixelTransceiver";
import { Experience } from "./experience/Experience";
import "./index.css";

const Studio = lazy(() => import("./App"));

export type AppView = "transceiver" | "experience" | "studio";

function RootApp() {
  const [view, setView] = useState<AppView>(() => {
    const params = new URLSearchParams(window.location.search);
    const v = params.get("view");
    if (v === "studio" || ["#studio", "#receiver"].includes(window.location.hash)) {
      return "studio";
    }
    if (v === "experience" || window.location.hash === "#experience") {
      return "experience";
    }
    // Default: The new Retro Pixel Transceiver UI based directly on the reference design
    return "transceiver";
  });

  useEffect(() => {
    const handlePopState = () => {
      const params = new URLSearchParams(window.location.search);
      const v = params.get("view");
      if (v === "studio") setView("studio");
      else if (v === "experience") setView("experience");
      else if (v === "transceiver") setView("transceiver");
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const navigateTo = (newView: AppView) => {
    setView(newView);
    const url = new URL(window.location.href);
    url.searchParams.set("view", newView);
    window.history.pushState({}, "", url.toString());
  };

  return (
    <>
      {view === "transceiver" && (
        <PixelTransceiver
          onBackToMain={() => navigateTo("experience")}
          onNavigate={navigateTo}
        />
      )}
      {view === "experience" && (
        <Experience onSwitchView={navigateTo} />
      )}
      {view === "studio" && (
        <Suspense
          fallback={
            <p className="loading-studio">Opening the transmission studio…</p>
          }
        >
          <Studio />
        </Suspense>
      )}
    </>
  );
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <RootApp />
  </React.StrictMode>,
);
