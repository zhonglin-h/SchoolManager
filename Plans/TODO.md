- [X] deeper analysis on AttendanceMonitor
- [X] the missing invitee one does not ignore the principal
- [X] implementation does NOT follow the idea that the unmatched invitee messages have to include also the participants that aren't matched yet
- [X] refactor the student/teacher split in MeetAttendanceHelper. Simplify
- [X] separate the refresh. Clicking refresh button should refresh everything. Otherwise, only thing that needs to be refreshed is the meet participants, current meeting status. No need to rebuild all checks again.
- [X] in NotificationService, it marks the people the notifications are about as recipients, when they are the subjects.

NEW FEATURES
- [ ] open meeting automatically
- [ ] Playwright: build a persistent long-lived runner that reuses one browser context/window across runs (new tab per run)
- [ ] package into manner than can be more portable
- [ ] create versioning and backups for database (online?)
- [ ] uptime checks
- [ ] edit msg for guest but not matched
- [ ] monitor how long teacher stayed there - when teacher leaves (also when arrived)
- [ ] support multiple email for people

- [ ] use telegram bot to create a new student record from an email + the name of the attendee. 
- [ ] add button in edith Person to reset the autolearned google id in case it's learned wrong.
- [ ] principal can ask the bot to give her the currently missing students. 
- [ ] use Pub/Sub topic + subscription + Google Cloud project 
