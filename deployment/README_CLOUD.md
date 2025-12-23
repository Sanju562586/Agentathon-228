# Guardian Mesh - Cloud Deployment Guide 🌩️

The backend is now "Cloud Native". You can deploy it to **Google Cloud Run**, **AWS App Runner**, or **Fly.io** without changes.

## Option 1: Google Cloud Run (Recommended for Scalability)
1.  **Create a Database**: Setup a free MongoDB Cluster on [MongoDB Atlas](https://www.mongodb.com/atlas/database) and get your Connection String (URI).
2.  Install [Google Cloud CLI](https://cloud.google.com/sdk/docs/install).
3.  Run the deployment command:

```powershell
gcloud run deploy guardian-gatekeeper `
  --source . `
  --platform managed `
  --region us-central1 `
  --allow-unauthenticated `
  --set-env-vars MONGO_URI="mongodb+srv://<username>:<password>@cluster.mongodb.net/..."
```
4.  **Copy the URL** (e.g., `https://guardian-gatekeeper-xyz.a.run.app`)
5.  Update `AppConfig.kt` in Android with this new URL.

## Option 2: Fly.io (Easiest for Prototypes)
1.  Run `fly launch` in `backend/` directory.
2.  Set the secret: `fly secrets set MONGO_URI="mongodb+srv://..."`

## Option 3: Docker (Generic)
```bash
docker build -t guardian-backend .
docker run -p 8080:8080 -e PORT=8080 -e MONGO_URI="mongodb+srv://..." guardian-backend
```
