# How to contribute

Thank you for considering donating your precious time! We welcome all contributions, and appreciate your effort in improving our project. To ensure a smooth contribution process, please follow the guidelines below.

## Feature Requests and Bug Reports

Please create a new [issue](https://github.com/AmadeusITGroup/GhRelAssetWagon/issues/new/choose) on our repository and choose "Feature request". This will allow us to review and prioritize your request accordingly.

## Pull Requests

When creating a pull request, please link the corresponding issue(if created) in the pull request description. This will help us track the progress of your contribution and ensure that it is reviewed in a timely manner.

In case you want to directly contribute to a code change or a bug fix, please follow the steps below:

1. Fork the repository.
2. Create a new branch for your feature or bug fix. (optional)
3. Make your changes and commit them to the branch of your choice.
4. Push the changes to your fork.
5. Create a pull request to merge your changes into the main repository.


### Commit messages

As much as possible, please follow the [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) guidelines.

Here are some examples of commit messages:

- For bugfix: `git commit -m "[fix|fixes|bugfix|bugfixes]: this is a commit message for a fix"`
- For feature: `git commit -m "[feat|feature|features]: this is a commit message for a feature"`
- For documentation: the commit message should contain the word `doc`, `docs` or `documentation`
- For breaking change: the commit message should contain the word `breaking`, `breaking change`, `breaking changes`, `breaking-change` or `breaking-changes`

Those are common examples, for more information don't hesitate to have a look at <https://github.com/conventional-changelog/commitlint/#what-is-commitlint>


### Rules for Contributions

When contributing, please keep in mind the following rules:

- Make only non-breaking changes in minor versions. Enhancements to existing code are possible - please discuss it beforehand using [issues](https://github.com/AmadeusITGroup/GhRelAssetWagon/issues/new/choose).
- Please ensure that you are submitting quality code, specifically make sure that the changes comply with our [code styling convention](#style-guide).

### Style guide

- Always write description comments for methods and properties
- A description comment must use the pattern `/** [Your comment] */`
- Linter tasks must pass
- Add relevant Unit Tests

## Code review process

After submitting a pull request, you will receive feedback from the maintainers. The review process will continue until the pull request is ready to be merged.

### Rules for reviewers

As a reviewer, please follow these guidelines:

- When using the `Request changes` option, please include at least one comment with a specific change required. A question alone does not count as a change requirement. This option indicates that the reviewer is not in favor of merging the pull request until the requested changes are made.
- When using the `Approved option`, the reviewer may still include change requests if desired. This option indicates that the reviewer is in favor of merging the pull request as-is, but suggests that the author may wish to consider making some small changes.
- Always stay polite and professional in your comments.
- The purpose of comments is to suggest improvements, ask a question or request for a change.
- Comments should be constructive and suggest ways to improve things.
- `Request changes` option shouldn't be used if the comments consist only of questions.

Thanks in advance for your contribution, and we look forward to hearing from you :)



**Note:** This document is inspired by the [Otter Library - How to contribute](https://github.com/AmadeusITGroup/otter/blob/main/CONTRIBUTING.md).