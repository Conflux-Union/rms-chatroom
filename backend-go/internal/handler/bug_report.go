package handler

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"

	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
)

const bugReportMaxSize = 10 * 1024 * 1024 // 10MB

// RegisterBugReportRoutes registers /api/bug routes.
func RegisterBugReportRoutes(g *echo.Group, bugReportsDir string) {
	os.MkdirAll(bugReportsDir, 0755)
	g.POST("/report", submitBugReport(bugReportsDir))
	g.GET("/report/:report_id", downloadBugReport(bugReportsDir))
}

func submitBugReport(dir string) echo.HandlerFunc {
	return func(c echo.Context) error {
		fh, err := c.FormFile("file")
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "no file provided"})
		}
		if filepath.Ext(fh.Filename) != ".zip" {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "file must be a .zip archive"})
		}
		if fh.Size > bugReportMaxSize {
			return c.JSON(http.StatusBadRequest, map[string]string{
				"error": fmt.Sprintf("file too large, max %dMB", bugReportMaxSize/1024/1024),
			})
		}

		src, err := fh.Open()
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to read file"})
		}
		defer src.Close()

		content, err := io.ReadAll(src)
		if err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to read file"})
		}

		reportID := uuid.New().String()
		if err := os.WriteFile(filepath.Join(dir, reportID+".zip"), content, 0644); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to save report"})
		}

		return c.JSON(http.StatusOK, map[string]string{"report_id": reportID})
	}
}

func downloadBugReport(dir string) echo.HandlerFunc {
	return func(c echo.Context) error {
		reportID := c.Param("report_id")
		path := filepath.Join(dir, reportID+".zip")
		if _, err := os.Stat(path); os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "report not found"})
		}
		return c.Attachment(path, fmt.Sprintf("bug_report_%s.zip", reportID))
	}
}
